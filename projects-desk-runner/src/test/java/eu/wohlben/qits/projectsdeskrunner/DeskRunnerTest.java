package eu.wohlben.qits.projectsdeskrunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projectsdeskrunner.protocol.DeskEntry;
import eu.wohlben.qits.projectsdeskrunner.protocol.DeskRunnerBinary;
import eu.wohlben.qits.projectsdeskrunner.protocol.DeskRunnerCodec;
import eu.wohlben.qits.projectsdeskrunner.protocol.DeskRunnerMessage;
import eu.wohlben.qits.projectsdeskrunner.protocol.DeskSpec;
import eu.wohlben.qits.projectsdeskrunner.protocol.DeskState;
import eu.wohlben.qits.projectsdeskrunner.protocol.DesiredState;
import eu.wohlben.qits.projectsdeskrunner.protocol.Estate;
import eu.wohlben.qits.projectsdeskrunner.protocol.HealthCheck;
import eu.wohlben.qits.projectsdeskrunner.protocol.HealthChecked;
import eu.wohlben.qits.projectsdeskrunner.protocol.HeldDesk;
import eu.wohlben.qits.projectsdeskrunner.protocol.Inventory;
import eu.wohlben.qits.projectsdeskrunner.protocol.LaunchFailed;
import eu.wohlben.qits.projectsdeskrunner.protocol.LoginPresence;
import eu.wohlben.qits.projectsdeskrunner.protocol.LoginState;
import eu.wohlben.qits.projectsdeskrunner.protocol.ProbeLogin;
import eu.wohlben.qits.projectsdeskrunner.protocol.Remove;
import eu.wohlben.qits.projectsdeskrunner.protocol.Removed;
import eu.wohlben.qits.projectsdeskrunner.protocol.Take;
import eu.wohlben.qits.runner.protocol.Ack;
import eu.wohlben.qits.runner.protocol.Backlog;
import eu.wohlben.qits.runner.protocol.Hello;
import eu.wohlben.qits.runner.protocol.Reserve;
import eu.wohlben.qits.runner.protocol.Retire;
import eu.wohlben.qits.runner.protocol.RunnerCapabilities;
import eu.wohlben.qits.runner.protocol.RunnerCodec;
import eu.wohlben.qits.runner.protocol.Upgrade;
import eu.wohlben.qits.runner.toolkit.Decommission;
import eu.wohlben.qits.runner.toolkit.HostFacts;
import eu.wohlben.qits.runner.toolkit.Housekeeping;
import eu.wohlben.qits.runner.toolkit.RunnerExit;
import eu.wohlben.qits.runner.toolkit.RunnerIdentity;
import eu.wohlben.qits.runner.toolkit.RunnerRuntime;
import eu.wohlben.qits.runner.toolkit.RunnerSettings;
import eu.wohlben.qits.runner.toolkit.SelfUpdate;
import eu.wohlben.qits.runner.toolkit.Telemetry;
import eu.wohlben.qits.runner.toolkit.testing.FakeDocker;
import eu.wohlben.qits.runner.toolkit.testing.FakeRunnerHost;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * The front-desk runner's life, end to end, against qits-runner-toolkit's own fixtures: {@link
 * FakeRunnerHost} (register door, token endpoint, control socket, each scripted here) and {@link
 * FakeDocker} (a script the production process seam forks as docker). The runtime is the toolkit's
 * {@link RunnerRuntime} with {@link DeskRunnerDomain}, exactly what {@code Main} hands {@code
 * RunnerLauncher}; only the node's clocks are shrunk. The shape is qits-workspaces-runner-daemon's
 * {@code WorkspacesRunnerTest}.
 */
@EnabledOnOs(OS.LINUX)
class DeskRunnerTest {

  private static final RunnerIdentity IDENTITY = DeskRunnerIdentity.IDENTITY;

  private static final RunnerCodec<DeskRunnerMessage> CODEC =
      new RunnerCodec<>(IDENTITY.vocabulary(), new DeskRunnerCodec());

  /** The runner's own container, as the suite names it ({@link RunnerRuntime.NodeOptions#self}). */
  private static final String SELF = "0123456789ab";

  private static final String RUNNER_ID = "r1";

  private static final String DESK_IMAGE = "registry.example:5000/qits/project-agent:2026.1008.1";

  private static final String P1 = "8273c743-e0bb-4d93-90d4-391aa10d9151";
  private static final String P2 = "5ca0e7e9-7dd8-4128-bbdd-58aa40d9b397";
  private static final String P3 = "dc112edc-4360-4a3b-858b-e75d372e708f";

  private static final String H1 = "sha256-hash-one";
  private static final String H2 = "sha256-hash-two";

  private static final String DOT_CLAUDE = "qits-projects-desk-runner-dot-claude-r1";
  private static final String M2 = "qits-projects-desk-runner-m2-r1";
  private static final String PNPM = "qits-projects-desk-runner-pnpm-r1";

  /** The platform host's shared volumes, as its install line names them. */
  private static final String SHARED_CLAUDE = "qits_shared_dot_claude";

  private static final String SHARED_M2 = "qits_shared_m2";
  private static final String SHARED_PNPM = "qits_shared_pnpm";

  private static final Map<String, String> SHARED_ENV =
      Map.of(
          "QITS_PROJECTS_DESK_RUNNER_CLAUDE_VOLUME", SHARED_CLAUDE,
          "QITS_PROJECTS_DESK_RUNNER_M2_VOLUME", SHARED_M2,
          "QITS_PROJECTS_DESK_RUNNER_PNPM_VOLUME", SHARED_PNPM);

  /** The mounts label of a desk run with the runner's own node volumes. */
  private static final String OWN_MOUNTS = new NodeVolumes(IDENTITY, RUNNER_ID).mountsHash();

  private final Vertx vertx = Vertx.vertx();
  private FakeRunnerHost host;
  private FakeDocker docker;
  private DeskRunnerDomain domain;
  private RunnerRuntime<DeskRunnerMessage> runner;
  private CompletableFuture<Integer> exit;

  /** The node's clocks: a 600 ms health window, a minute's inventory tick, a minute's probes. */
  private DeskRunnerDomain.Timing timing =
      new DeskRunnerDomain.Timing(
          Duration.ofMillis(600),
          Duration.ofMillis(50),
          Duration.ofSeconds(60),
          Duration.ofSeconds(60),
          Duration.ofSeconds(60));

  private long housekeepingMillis = TimeUnit.HOURS.toMillis(6);

  /** Variables beside the toolkit's, e.g. the external node volumes. */
  private final Map<String, String> extraEnv = new HashMap<>();

  @TempDir Path state;
  @TempDir Path dockerDir;

  @BeforeEach
  void setUp() throws Exception {
    host = new FakeRunnerHost(vertx, IDENTITY, CODEC);
    docker = new FakeDocker(dockerDir);
    // Every login probe answers "logged in" unless a case says otherwise, so no probe leaves a
    // container in the fake's state.
    docker.answerFor("run", "status", 0, "{\"loggedIn\": true}\n", "");
  }

  @AfterEach
  void tearDown() throws Exception {
    stopRunner();
    if (domain != null) {
      domain.close();
    }
    host.close();
    vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
  }

  // ---- register and hello -----------------------------------------------------------------------

  @Test
  void itRegistersAtTheProjectsDoorAndSaysHelloAsTheFrontDeskRunner() throws Exception {
    ackWith(8, 0);
    start();

    Hello hello = host.await(Hello.class);
    assertEquals(List.of("/projects/api/runners/r1/register"), host.registerPaths);
    assertEquals("Bearer token-1", host.registerAuthorizations.getFirst());
    assertEquals(DeskRunnerBinary.VERSION, hello.runnerVersion());
    assertEquals(1, hello.capabilityVersion());
    assertEquals(new RunnerCapabilities(true, "amd64", "linux", Map.of()), hello.capabilities());
    assertEquals(List.of(), hello.held());
    assertFalse(CODEC.encode(hello).containsKey("heldDesks"), "absent on a first connection");
  }

  // ---- convergence --------------------------------------------------------------------------------

  @Test
  void anEstateRunsAnAbsentDeskPulledUnderTheRunnersLoginWithItsVolumesMadeLabelledFirst()
      throws Exception {
    docker.answerFor("image-inspect", DESK_IMAGE, 1, "", "Error: No such image");
    estateThenAck(entry(P1, DesiredState.RUNNING, H1));
    start();

    awaitTrue(() -> !runsOf(desk(P1)).isEmpty());
    List<String> run = runOf(desk(P1));
    assertEquals(
        List.of(
            "run",
            "-d",
            "--init",
            "--name",
            desk(P1),
            "--network",
            "bridge",
            "--user",
            "1000",
            "--label",
            "qits.projects.desk.runner=r1",
            "--label",
            "qits.projects.desk.runner.mounts=" + OWN_MOUNTS,
            "--label",
            "qits.projects.desk.runner.project=" + P1,
            "--label",
            "qits.projects.desk.runner.spec-hash=" + H1,
            "--memory",
            "4g",
            "--memory-swap",
            "4g",
            "--pids-limit",
            "512",
            "--cpus",
            "2",
            "--oom-score-adj",
            "800",
            "--restart",
            "unless-stopped",
            "-v",
            desk(P1) + ":/workspace",
            "-v",
            DOT_CLAUDE + ":/claude-home",
            "-v",
            M2 + ":/caches/m2",
            "-v",
            PNPM + ":/caches/pnpm",
            "-e",
            "QITS_TOKEN=qits_tok_DESK",
            "-e",
            "TZ=UTC",
            DESK_IMAGE),
        run,
        "the default bridge, no extra host, no docker socket, --init, unless-stopped");
    List<List<String>> calls = docker.calls();
    List<String> pull =
        calls.stream()
            .filter(c -> c.contains("pull"))
            .findFirst()
            .orElseThrow(() -> new AssertionError(calls));
    assertEquals("--config", pull.getFirst(), "pulled under the runner's own throwaway login");
    assertEquals(List.of("pull", DESK_IMAGE), FakeDocker.withoutConfig(pull));
    assertTrue(calls.indexOf(pull) < calls.indexOf(run), "pulled before it is run: " + calls);
    // Not a global order: onAck's ensure, the reconcile, housekeeping and the check run
    // concurrently, so other passes' volume calls may land anywhere. What matters is that every
    // volume the run mounts was created labelled before that run.
    int runAt = calls.indexOf(run);
    for (String mounted : List.of(desk(P1), DOT_CLAUDE, M2, PNPM)) {
      int created = -1;
      for (int i = 0; i < runAt; i++) {
        List<String> c = calls.get(i);
        if (c.size() > 2
            && c.get(0).equals("volume")
            && c.get(1).equals("create")
            && c.getLast().equals(mounted)
            && c.contains("--label")) {
          created = i;
          break;
        }
      }
      assertTrue(created >= 0, mounted + " created labelled before its run: " + calls);
    }
    assertEquals(
        Map.of("qits.projects.desk.runner", RUNNER_ID, "qits.projects.desk.runner.project", P1),
        volumes().get(desk(P1)));
    for (String node : List.of(DOT_CLAUDE, M2, PNPM)) {
      assertEquals(Map.of("qits.projects.desk.runner", RUNNER_ID), volumes().get(node), node);
    }
    // One inventory at the Ack, one after the convergence.
    awaitTrue(() -> host.all(Inventory.class).size() >= 2);
  }

  @Test
  void aRunningDeskOfTheSameSpecIsLeftAloneAndAnExitedOneIsStartedAgain() throws Exception {
    seen(P1, "running", H1);
    seen(P2, "exited", H2);
    estateThenAck(entry(P1, DesiredState.RUNNING, H1), entry(P2, DesiredState.RUNNING, H2));
    start();

    awaitCall(c -> has(c, List.of("start", desk(P2))));
    awaitTrue(() -> host.all(Inventory.class).size() >= 2);
    assertTrue(runsOf(desk(P1)).isEmpty() && runsOf(desk(P2)).isEmpty(), docker.calls()::toString);
    for (String verb : List.of("start", "stop", "rm")) {
      assertFalse(touched(verb, desk(P1)), docker.calls()::toString);
    }
    assertFalse(touched("rm", desk(P2)), "the same container, not a second one");
  }

  @Test
  void aDeskOfAnotherSpecHashIsStoppedRemovedAndRunAgainKeepingItsVolume() throws Exception {
    seen(P1, "running", "an-older-hash");
    Map<String, String> labels =
        Map.of("qits.projects.desk.runner", RUNNER_ID, "qits.projects.desk.runner.project", P1);
    docker.volume(desk(P1), labels);
    estateThenAck(entry(P1, DesiredState.RUNNING, H1));
    start();

    awaitTrue(() -> !runsOf(desk(P1)).isEmpty());
    List<List<String>> calls = docker.calls().stream().map(FakeDocker::withoutConfig).toList();
    int stop = calls.indexOf(List.of("stop", desk(P1)));
    int removed = calls.indexOf(List.of("rm", "-f", desk(P1)));
    int run = calls.indexOf(runOf(desk(P1)));
    assertTrue(stop >= 0 && removed > stop && run > removed, "stop, rm, run: " + calls);
    assertTrue(runOf(desk(P1)).contains("qits.projects.desk.runner.spec-hash=" + H1));
    assertEquals(labels, volumes().get(desk(P1)), "the volume is the work; it stays");
    assertTrue(docker.calls("volume").stream().noneMatch(c -> c.contains("rm")), calls::toString);
  }

  @Test
  void desiredStoppedStopsTheDeskKeepsItsContainerAndVolumeAndFreesTheSlot() throws Exception {
    seen(P1, "running", H1);
    docker.volume(
        desk(P1),
        Map.of("qits.projects.desk.runner", RUNNER_ID, "qits.projects.desk.runner.project", P1));
    estateThenAck(entry(P1, DesiredState.RUNNING, H1));
    start();
    awaitTrue(() -> runner.session().held().equals(List.of(P1)));

    host.send(new Estate(List.of(entry(P1, DesiredState.STOPPED, H1)), DESK_IMAGE));
    awaitCall(c -> has(c, List.of("stop", desk(P1))));
    awaitTrue(() -> runner.session().held().isEmpty());
    assertTrue(containers().containsKey(desk(P1)), "stopped, not removed");
    assertTrue(volumes().containsKey(desk(P1)), "the volume is kept");
    assertFalse(touched("rm", desk(P1)), docker.calls()::toString);
  }

  @Test
  void removeStopsThenRemovesTheContainerAndTheDeskVolumeOnlyThenSaysRemoved() throws Exception {
    seen(P1, "running", H1);
    docker.volume(
        desk(P1),
        Map.of("qits.projects.desk.runner", RUNNER_ID, "qits.projects.desk.runner.project", P1));
    estateThenAck(entry(P1, DesiredState.RUNNING, H1));
    start();
    awaitTrue(() -> runner.session().held().equals(List.of(P1)));

    host.send(new Remove(P1));
    assertEquals(new Removed(P1), host.await(Removed.class));
    assertFalse(containers().containsKey(desk(P1)));
    assertFalse(volumes().containsKey(desk(P1)));
    List<List<String>> calls = docker.calls().stream().map(FakeDocker::withoutConfig).toList();
    int stop = calls.lastIndexOf(List.of("stop", desk(P1)));
    int rm = calls.indexOf(List.of("rm", "-f", desk(P1)));
    assertTrue(stop >= 0 && rm > stop, "a graceful stop, then rm -f: " + calls);
    assertTrue(volumes().containsKey(DOT_CLAUDE), "the node's agent home is no desk's");
    awaitTrue(() -> runner.session().held().isEmpty());

    host.send(new Remove(P2));
    assertEquals(new Removed(P2), host.await(Removed.class, 2), "an absent desk is removed too");
  }

  @Test
  void nothingIsReapedBeforeTheFirstEstateAndThenOnlyTheUnlistedDesksOfThisRunner()
      throws Exception {
    housekeepingMillis = 200;
    seen(P1, "running", H1);
    seen(P3, "exited", H2);
    Map<String, String> desk1 =
        Map.of("qits.projects.desk.runner", RUNNER_ID, "qits.projects.desk.runner.project", P1);
    Map<String, String> desk3 =
        Map.of("qits.projects.desk.runner", RUNNER_ID, "qits.projects.desk.runner.project", P3);
    Map<String, String> otherRunners =
        Map.of("qits.projects.desk.runner", "r2", "qits.projects.desk.runner.project", P2);
    docker.volume(desk(P1), desk1);
    docker.volume(desk(P3), desk3);
    // The node's other tenants: names like ours without our label, and another runner's desk.
    docker.container(desk(P3) + "-copy", Map.of());
    docker.container("qits-projects-desk-runner-desk-" + P2 + "-r2", otherRunners);
    docker.volume("qits-projects-desk-runner-desk-" + P2 + "-r1", Map.of());
    docker.volume("qits-projects-desk-runner-desk-" + P2 + "-r2", otherRunners);
    ackWith(1, 0);
    start();
    host.await(Inventory.class);

    // Several housekeeping passes, and no estate: nothing goes.
    Thread.sleep(1_000);
    assertTrue(containers().keySet().containsAll(Set.of(desk(P1), desk(P3))));
    assertTrue(volumes().keySet().containsAll(Set.of(desk(P1), desk(P3))));
    assertTrue(docker.calls("rm").isEmpty(), docker.calls()::toString);
    assertTrue(
        docker.calls("volume").stream().noneMatch(c -> c.contains("rm")), docker.calls()::toString);

    host.send(new Estate(List.of(entry(P1, DesiredState.RUNNING, H1)), DESK_IMAGE));
    awaitTrue(() -> !containers().containsKey(desk(P3)));
    awaitTrue(() -> !volumes().containsKey(desk(P3)));

    assertEquals(
        Set.of(desk(P1), desk(P3) + "-copy", "qits-projects-desk-runner-desk-" + P2 + "-r2"),
        containers().keySet());
    assertEquals(
        Set.of(
            desk(P1),
            DOT_CLAUDE,
            M2,
            PNPM,
            "qits-projects-desk-runner-desk-" + P2 + "-r1",
            "qits-projects-desk-runner-desk-" + P2 + "-r2"),
        volumes().keySet());
    awaitTrue(
        () ->
            host.all(Inventory.class).stream()
                .anyMatch(
                    i -> i.desks().equals(List.of(new HeldDesk(P1, DeskState.RUNNING, H1, null)))));
  }

  // ---- take, reserve and the slots ----------------------------------------------------------------

  @Test
  void aTakeDockerRefusesIsLaunchFailedWithDockersLastWordsAndHoldsNoSlot() throws Exception {
    docker.answerFor(
        "run",
        DESK_IMAGE,
        125,
        "",
        "docker: Error response from daemon: no space left on device.");
    host.script =
        (h, message) -> {
          if (message instanceof Hello) {
            h.send(new Ack(IDENTITY.capabilityVersion(), 1));
            h.send(new Backlog(2));
          } else if (message instanceof Reserve && h.all(Reserve.class).size() <= 2) {
            String project = h.all(Reserve.class).size() == 1 ? P1 : P2;
            DeskEntry e = entry(project, DesiredState.RUNNING, H1);
            h.send(new Take(e.projectId(), e.desired(), e.spec(), e.specHash()));
          }
        };
    start();

    LaunchFailed first = host.await(LaunchFailed.class, 1);
    assertEquals(P1, first.projectId());
    assertTrue(first.detail().contains("no space left on device"), first.detail());
    // The second Reserve proves the refused take was answered to the ledger (taken, released).
    assertEquals(P2, host.await(LaunchFailed.class, 2).projectId());
    awaitTrue(() -> runner.session().held().isEmpty());
    awaitTrue(
        () ->
            host.all(Inventory.class).getLast().desks().stream()
                .anyMatch(
                    d ->
                        d.projectId().equals(P1)
                            && d.state() == DeskState.FAILED
                            && d.detail().contains("no space left on device")));
  }

  @Test
  void aReserveGoesOutOnlyWhileFewerDesksRunThanTheRunnerHasSlots() throws Exception {
    seen(P1, "running", H1);
    seen(P2, "running", H2);
    host.script =
        (h, message) -> {
          if (message instanceof Hello) {
            h.send(new Ack(IDENTITY.capabilityVersion(), 2));
            h.send(new Backlog(1));
            h.send(
                new Estate(
                    List.of(entry(P1, DesiredState.RUNNING, H1), entry(P2, DesiredState.RUNNING, H2)),
                    DESK_IMAGE));
          }
        };
    start();
    awaitTrue(() -> Set.copyOf(runner.session().held()).equals(Set.of(P1, P2)));
    host.await(LoginState.class);
    Thread.sleep(500);
    assertTrue(host.all(Reserve.class).isEmpty(), "two running desks fill two slots");

    host.send(
        new Estate(
            List.of(entry(P1, DesiredState.RUNNING, H1), entry(P2, DesiredState.STOPPED, H2)),
            DESK_IMAGE));
    host.await(Reserve.class);
    assertEquals(List.of(P1), runner.session().held());
  }

  // ---- the inventory ------------------------------------------------------------------------------

  @Test
  void theInventoryGoesOutOnEveryTickAndNeverFromAListingDockerDidNotAnswer() throws Exception {
    timing =
        new DeskRunnerDomain.Timing(
            Duration.ofMillis(600),
            Duration.ofMillis(50),
            Duration.ofMillis(100),
            Duration.ofSeconds(60),
            Duration.ofSeconds(60));
    seen(P1, "running", H1);
    ackWith(1, 0);
    start();
    host.await(Inventory.class, 4);
    assertEquals(
        List.of(new HeldDesk(P1, DeskState.RUNNING, H1, null)),
        host.all(Inventory.class).getLast().desks());

    docker.answer("ps", 1, "", "Cannot connect to the Docker daemon");
    Thread.sleep(300);
    int settled = host.all(Inventory.class).size();
    Thread.sleep(600);
    assertEquals(settled, host.all(Inventory.class).size(), "a failed listing is never sent");
  }

  // ---- the login ----------------------------------------------------------------------------------

  @Test
  void theLoginIsProbedAtTheGreetingAsTheDesksUserAndAgainOnProbeLogin() throws Exception {
    docker.answerFor("run", "status", 1, "{\"loggedIn\": false}\n", "");
    seen(P1, "running", H1);
    estateThenAck(entry(P1, DesiredState.RUNNING, H1));
    start();

    assertEquals(new LoginState(LoginPresence.ABSENT), host.await(LoginState.class));
    List<String> probe =
        docker.calls("run").stream()
            .filter(c -> c.contains("status"))
            .findFirst()
            .orElseThrow(() -> new AssertionError(docker.calls()));
    assertEquals(
        List.of(
            "run",
            "--rm",
            "--label",
            "qits.projects.desk.runner=r1",
            "--user",
            "1000",
            "--entrypoint",
            "claude",
            "-v",
            DOT_CLAUDE + ":/claude-home",
            "-e",
            "HOME=/claude-home",
            "-e",
            "CLAUDE_CONFIG_DIR=/claude-home/.claude",
            DESK_IMAGE,
            "auth",
            "status"),
        probe);
    assertEquals(Map.of("qits.projects.desk.runner", RUNNER_ID), volumes().get(DOT_CLAUDE));

    // The operator logs in on the node.
    docker.answerFor("run", "status", 0, "{\"loggedIn\": true}\n", "");
    host.send(new ProbeLogin());
    assertEquals(new LoginState(LoginPresence.PRESENT), host.await(LoginState.class, 2));
  }

  @Test
  void aProbeThatCannotTellSendsNoLoginState() throws Exception {
    ackWith(1, 0);
    start();
    host.await(Inventory.class);

    host.send(new ProbeLogin());
    Thread.sleep(500);
    assertTrue(host.all(LoginState.class).isEmpty(), "no image to probe with yet");
    assertTrue(docker.calls("run").isEmpty());
  }

  // ---- the health check ---------------------------------------------------------------------------

  @Test
  void theHealthCheckPullsTheImageRunsItWithNoEnvSeesItRunningRemovesItAndAnswers()
      throws Exception {
    String name = "qits-projects-desk-runner-health-check-r1";
    docker.answerFor("image-inspect", DESK_IMAGE, 1, "", "Error: No such image");
    docker.answerFor("inspect", name, 0, "abc|running/none|2026-10-08T10:00:00Z\n", "");
    ackWith(1, 0);
    start();
    host.await(Inventory.class);

    host.send(new HealthCheck("req-1", DESK_IMAGE));
    HealthChecked checked = host.await(HealthChecked.class);
    assertEquals("req-1", checked.requestId());
    assertTrue(checked.ok(), checked.detail());

    List<List<String>> calls = docker.calls();
    List<String> pull =
        calls.stream()
            .filter(c -> c.contains("pull"))
            .findFirst()
            .orElseThrow(() -> new AssertionError(calls));
    assertEquals("--config", pull.getFirst(), "pulled under the runner's own throwaway login");
    List<String> run = runOf(name);
    assertEquals(
        List.of(
            "run",
            "-d",
            "--name",
            name,
            "--network",
            "bridge",
            "--label",
            "qits.projects.desk.runner=r1",
            DESK_IMAGE),
        run,
        "no env, no mount, no restart policy, the runner's ownership label only");
    assertTrue(calls.indexOf(pull) < calls.indexOf(run), "pulled before it is run: " + calls);
    assertFalse(containers().containsKey(name), "removed again");
  }

  @Test
  void aHealthCheckWhoseImageNeverRunsFailsAndStillRemovesItsContainer() throws Exception {
    String name = "qits-projects-desk-runner-health-check-r1";
    docker.answerFor("inspect", name, 0, "abc|exited/none|2026-10-08T10:00:00Z\n", "");
    ackWith(1, 0);
    start();
    host.await(Inventory.class);

    host.send(new HealthCheck("req-2", DESK_IMAGE));
    HealthChecked checked = host.await(HealthChecked.class);
    assertEquals("req-2", checked.requestId());
    assertFalse(checked.ok());
    assertTrue(checked.detail().contains("did not run"), checked.detail());
    assertTrue(docker.calls("pull").isEmpty(), "an image already here is not pulled again");
    assertFalse(containers().containsKey(name));
  }

  // ---- rollover and decommission ------------------------------------------------------------------

  @Test
  void anUpgradeWhileTwoDesksRunStartsTheSuccessorAtOnceAndTouchesNeither() throws Exception {
    runnerContainer();
    seen(P1, "running", H1);
    seen(P2, "running", H2);
    estateThenAck(entry(P1, DesiredState.RUNNING, H1), entry(P2, DesiredState.RUNNING, H2));
    start();
    host.await(LoginState.class);

    host.send(new Upgrade(NEXT, IMAGE, DIGEST));
    awaitCall(
        c ->
            c.stream()
                .anyMatch(
                    call ->
                        call.getFirst().equals("run")
                            && call.contains(IDENTITY.containerName(RUNNER_ID, NEXT))));

    host.send(new Retire("superseded by " + NEXT));
    assertEquals(RunnerExit.OK, exit.get(10, TimeUnit.SECONDS));
    runner = null;
    assertTrue(containers().containsKey(desk(P1)));
    assertTrue(containers().containsKey(desk(P2)));
    for (String verb : List.of("stop", "rm", "start", "update")) {
      assertFalse(touched(verb, desk(P1)), docker.calls()::toString);
      assertFalse(touched(verb, desk(P2)), docker.calls()::toString);
    }
  }

  @Test
  void aDeletedRunnerStopsItsDesksAndLeavesTheRemovalToTheToolkitsHelper() throws Exception {
    seen(P1, "running", H1);
    estateThenAck(entry(P1, DesiredState.RUNNING, H1));
    start();
    host.await(LoginState.class);

    host.send(Retire.deleted("deleted by admin"));
    assertEquals(RunnerExit.OK, exit.get(10, TimeUnit.SECONDS));
    runner = null;
    assertTrue(has(docker.calls(), List.of("stop", desk(P1))), docker.calls()::toString);
    assertFalse(touched("rm", desk(P1)), "the decommission helper removes it, not the domain");
  }

  // ---- external node volumes (the platform host's shared agent home and caches) -------------------

  @Test
  void externalVolumesAreMountedByTheDeskAndTheLoginProbeAndNeverCreatedOrLabelled()
      throws Exception {
    sharedVolumes();
    estateThenAck(entry(P1, DesiredState.RUNNING, H1));
    start();

    awaitTrue(() -> !runsOf(desk(P1)).isEmpty());
    List<String> run = runOf(desk(P1));
    String joined = String.join(" ", run);
    assertTrue(joined.contains("-v " + desk(P1) + ":/workspace"), joined);
    assertTrue(joined.contains("-v " + SHARED_CLAUDE + ":/claude-home"), joined);
    assertTrue(joined.contains("-v " + SHARED_M2 + ":/caches/m2"), joined);
    assertTrue(joined.contains("-v " + SHARED_PNPM + ":/caches/pnpm"), joined);
    assertTrue(joined.contains("qits.projects.desk.runner.mounts=" + sharedMounts()), joined);
    assertFalse(sharedMounts().equals(OWN_MOUNTS));

    host.await(LoginState.class);
    List<String> probe =
        docker.calls("run").stream()
            .filter(c -> c.contains("status"))
            .findFirst()
            .orElseThrow(() -> new AssertionError(docker.calls()));
    assertTrue(probe.contains(SHARED_CLAUDE + ":/claude-home"), probe::toString);

    for (String shared : List.of(SHARED_CLAUDE, SHARED_M2, SHARED_PNPM)) {
      assertEquals(Map.of(), volumes().get(shared), "never labelled: " + shared);
      assertTrue(
          docker.calls("volume").stream()
              .noneMatch(c -> c.contains(shared) && !c.contains("inspect")),
          "only ever inspected: " + docker.calls("volume"));
    }
    for (String own : List.of(DOT_CLAUDE, M2, PNPM)) {
      assertFalse(volumes().containsKey(own), "the runner's own is not made: " + own);
    }
    assertEquals(
        Map.of("qits.projects.desk.runner", RUNNER_ID, "qits.projects.desk.runner.project", P1),
        volumes().get(desk(P1)),
        "the desk's own volume is still the runner's");
  }

  @Test
  void onlyTheExternalOnesAreLeftAloneTheRunnersOwnNodeVolumesAreStillMade() throws Exception {
    extraEnv.put("QITS_PROJECTS_DESK_RUNNER_CLAUDE_VOLUME", SHARED_CLAUDE);
    docker.volume(SHARED_CLAUDE, Map.of());
    docker.answerFor("volume-inspect", SHARED_CLAUDE, 0, SHARED_CLAUDE + "\n", "");
    estateThenAck(entry(P1, DesiredState.RUNNING, H1));
    start();

    awaitTrue(() -> !runsOf(desk(P1)).isEmpty());
    String joined = String.join(" ", runOf(desk(P1)));
    assertTrue(joined.contains("-v " + SHARED_CLAUDE + ":/claude-home"), joined);
    assertTrue(joined.contains("-v " + M2 + ":/caches/m2"), joined);
    assertTrue(joined.contains("-v " + PNPM + ":/caches/pnpm"), joined);
    assertEquals(Map.of(), volumes().get(SHARED_CLAUDE));
    assertFalse(volumes().containsKey(DOT_CLAUDE));
    for (String own : List.of(M2, PNPM)) {
      assertEquals(Map.of("qits.projects.desk.runner", RUNNER_ID), volumes().get(own), own);
    }
  }

  @Test
  void aMissingExternalVolumeIsLaunchFailedAndNothingIsRunOrCreated() throws Exception {
    extraEnv.putAll(SHARED_ENV);
    for (String shared : List.of(SHARED_M2, SHARED_PNPM)) {
      docker.volume(shared, Map.of());
      docker.answerFor("volume-inspect", shared, 0, shared + "\n", "");
    }
    docker.answerFor(
        "volume-inspect",
        SHARED_CLAUDE,
        1,
        "",
        "Error response from daemon: get " + SHARED_CLAUDE + ": no such volume");
    estateThenAck(entry(P1, DesiredState.RUNNING, H1));
    start();

    LaunchFailed failed = host.await(LaunchFailed.class);
    assertEquals(P1, failed.projectId());
    assertEquals(
        "node volume "
            + SHARED_CLAUDE
            + " named by QITS_PROJECTS_DESK_RUNNER_CLAUDE_VOLUME does not exist",
        failed.detail());
    assertTrue(runsOf(desk(P1)).isEmpty(), docker.calls()::toString);
    assertTrue(
        docker.calls("volume").stream().noneMatch(c -> c.contains("create")),
        docker.calls()::toString);
    assertFalse(volumes().containsKey(SHARED_CLAUDE), "docker was never left to make it");
    awaitTrue(() -> runner.session().held().isEmpty());
    // Nor does the login probe make it: it cannot tell, so it says nothing.
    Thread.sleep(300);
    assertTrue(host.all(LoginState.class).isEmpty(), host.all(LoginState.class)::toString);
    assertTrue(
        docker.calls("run").stream().noneMatch(c -> c.contains("status")),
        docker.calls()::toString);
  }

  @Test
  void aDeskRunWithTheRunnersOwnVolumesIsRecreatedOnTheExternalOnesKeepingItsWorkspace()
      throws Exception {
    sharedVolumes();
    seen(P1, "running", H1, OWN_MOUNTS);
    seen(P2, "running", H2, null); // run before the mounts label existed
    Map<String, String> labels =
        Map.of("qits.projects.desk.runner", RUNNER_ID, "qits.projects.desk.runner.project", P1);
    docker.volume(desk(P1), labels);
    estateThenAck(entry(P1, DesiredState.RUNNING, H1), entry(P2, DesiredState.RUNNING, H2));
    start();

    awaitTrue(() -> !runsOf(desk(P1)).isEmpty() && !runsOf(desk(P2)).isEmpty());
    List<List<String>> calls = docker.calls().stream().map(FakeDocker::withoutConfig).toList();
    for (String project : List.of(P1, P2)) {
      int stop = calls.indexOf(List.of("stop", desk(project)));
      int removed = calls.indexOf(List.of("rm", "-f", desk(project)));
      int run = calls.indexOf(runOf(desk(project)));
      assertTrue(stop >= 0 && removed > stop && run > removed, "stop, rm, run: " + calls);
      String joined = String.join(" ", runOf(desk(project)));
      assertTrue(joined.contains("-v " + SHARED_CLAUDE + ":/claude-home"), joined);
      assertTrue(joined.contains("qits.projects.desk.runner.mounts=" + sharedMounts()), joined);
    }
    assertEquals(labels, volumes().get(desk(P1)), "the workspace volume is the work; it stays");
    assertTrue(
        docker.calls("volume").stream().noneMatch(c -> c.contains("rm")), calls::toString);
  }

  @Test
  void aDeskAlreadyOnTheExternalVolumesIsLeftAlone() throws Exception {
    sharedVolumes();
    seen(P1, "running", H1, sharedMounts());
    estateThenAck(entry(P1, DesiredState.RUNNING, H1));
    start();

    awaitTrue(() -> runner.session().held().equals(List.of(P1)));
    host.await(LoginState.class);
    for (String verb : List.of("start", "stop", "rm")) {
      assertFalse(touched(verb, desk(P1)), docker.calls()::toString);
    }
    assertTrue(runsOf(desk(P1)).isEmpty(), docker.calls()::toString);
  }

  @Test
  void housekeepingRemovesAnUnlistedDeskVolumeButNeverAnExternalOne() throws Exception {
    housekeepingMillis = 200;
    sharedVolumes();
    seen(P1, "running", H1, sharedMounts());
    seen(P3, "exited", H2, sharedMounts());
    docker.volume(
        desk(P3),
        Map.of("qits.projects.desk.runner", RUNNER_ID, "qits.projects.desk.runner.project", P3));
    estateThenAck(entry(P1, DesiredState.RUNNING, H1));
    start();

    awaitTrue(() -> !volumes().containsKey(desk(P3)));
    Thread.sleep(600); // several passes more
    for (String shared : List.of(SHARED_CLAUDE, SHARED_M2, SHARED_PNPM)) {
      assertEquals(Map.of(), volumes().get(shared), shared);
    }
    assertTrue(
        docker.calls("volume").stream()
            .filter(c -> c.contains("rm"))
            .noneMatch(c -> c.stream().anyMatch(a -> a.startsWith("qits_shared_"))),
        docker.calls()::toString);
  }

  @Test
  void theDecommissionHelperRemovesTheRunnersOwnVolumesAndNeverAnExternalOne() throws Exception {
    sharedVolumes();
    Map<String, String> own = Map.of("qits.projects.desk.runner", RUNNER_ID);
    docker.volume(M2, own);
    docker.volume(
        desk(P1),
        Map.of("qits.projects.desk.runner", RUNNER_ID, "qits.projects.desk.runner.project", P1));
    // What a careless runner could have done: the shared volume mounted by a labelled desk.
    docker.container(desk(P1), Map.of("qits.projects.desk.runner", RUNNER_ID));

    Map<String, String> env = new HashMap<>(SHARED_ENV);
    env.put(IDENTITY.env(eu.wohlben.qits.runner.toolkit.RunnerLauncher.DECOMMISSION), RUNNER_ID);
    env.put(IDENTITY.env(RunnerSettings.DOCKER_BINARY), docker.binary());
    int code =
        eu.wohlben.qits.runner.toolkit.RunnerLauncher.main(
            IDENTITY,
            DeskRunnerDomain::new,
            null,
            new String[0],
            env::get,
            new java.io.PrintStream(new java.io.ByteArrayOutputStream(), true));

    assertEquals(RunnerExit.OK, code);
    assertFalse(volumes().containsKey(M2), docker.calls()::toString);
    assertFalse(volumes().containsKey(desk(P1)), docker.calls()::toString);
    for (String shared : List.of(SHARED_CLAUDE, SHARED_M2, SHARED_PNPM)) {
      assertTrue(volumes().containsKey(shared), "the helper never reaches " + shared);
    }
  }

  @Test
  void aSuccessorKeepsTheExternalVolumeVariables() throws Exception {
    runnerContainer(
        List.of(
            "QITS_PROJECTS_DESK_RUNNER_CLAUDE_VOLUME=" + SHARED_CLAUDE,
            "QITS_PROJECTS_DESK_RUNNER_M2_VOLUME=" + SHARED_M2,
            "QITS_PROJECTS_DESK_RUNNER_PNPM_VOLUME=" + SHARED_PNPM));
    sharedVolumes();
    estateThenAck();
    start();
    host.await(Inventory.class);

    host.send(new Upgrade(NEXT, IMAGE, DIGEST));
    awaitCall(
        c ->
            c.stream()
                .anyMatch(
                    call ->
                        call.getFirst().equals("run")
                            && call.contains(IDENTITY.containerName(RUNNER_ID, NEXT))));
    List<String> successor =
        docker.calls("run").stream()
            .filter(call -> call.contains(IDENTITY.containerName(RUNNER_ID, NEXT)))
            .findFirst()
            .orElseThrow();
    for (String variable :
        List.of(
            "QITS_PROJECTS_DESK_RUNNER_CLAUDE_VOLUME=" + SHARED_CLAUDE,
            "QITS_PROJECTS_DESK_RUNNER_M2_VOLUME=" + SHARED_M2,
            "QITS_PROJECTS_DESK_RUNNER_PNPM_VOLUME=" + SHARED_PNPM)) {
      int at = successor.indexOf(variable);
      assertTrue(at > 0 && successor.get(at - 1).equals("-e"), variable + ": " + successor);
    }
    host.send(new Retire("superseded by " + NEXT));
    assertEquals(RunnerExit.OK, exit.get(10, TimeUnit.SECONDS));
    runner = null;
  }

  // ---- the runner, the host's scripts and the node -------------------------------------------------

  private void start() throws Exception {
    Map<String, String> env = new HashMap<>();
    env.put(IDENTITY.env(RunnerSettings.URL), host.base());
    env.put(IDENTITY.env(RunnerSettings.ID), RUNNER_ID);
    env.put(IDENTITY.env(RunnerSettings.REGISTRATION_TOKEN), "token-1");
    env.put(IDENTITY.env(RunnerSettings.STATE_DIR), state.toString());
    env.put(IDENTITY.env(RunnerSettings.SLOTS), "2");
    env.put(IDENTITY.env(RunnerSettings.DOCKER_BINARY), docker.binary());
    env.put(IDENTITY.env(RunnerSettings.DOCKER_TIMEOUT), "10");
    env.putAll(extraEnv);
    RunnerSettings settings =
        RunnerSettings.parse(IDENTITY, env::get)
            .withTiming(new RunnerSettings.Timing(10_000, 50, 200, 5_000, 300_000, 5));
    domain =
        new DeskRunnerDomain(
            settings, ExternalVolumes.parse(IDENTITY, env::get), timing, Clock.systemUTC());
    runner =
        new RunnerRuntime<>(
            vertx,
            IDENTITY,
            settings,
            domain,
            new HostFacts(true, "amd64", "linux"),
            Telemetry.off(),
            new RunnerRuntime.NodeOptions(
                Optional.of(SELF),
                new SelfUpdate.Settings(100, 400, 3_000, 400, 50),
                new Decommission.Settings(200, 20, 2),
                new Housekeeping.Settings(housekeepingMillis),
                Clock.systemUTC()));
    exit = CompletableFuture.supplyAsync(runner::run);
  }

  private void stopRunner() throws Exception {
    if (runner != null) {
      runner.stop(RunnerExit.OK);
      exit.get(10, TimeUnit.SECONDS);
      runner = null;
    }
  }

  private void ackWith(int slots, int backlog) {
    host.script =
        (h, message) -> {
          if (message instanceof Hello) {
            h.send(new Ack(IDENTITY.capabilityVersion(), slots));
            h.send(new Backlog(backlog));
          }
        };
  }

  /** Ack every Hello, then the estate the server sends right after it. */
  private void estateThenAck(DeskEntry... desks) {
    host.script =
        (h, message) -> {
          if (message instanceof Hello) {
            h.send(new Ack(IDENTITY.capabilityVersion(), 2));
            h.send(new Estate(List.of(desks), DESK_IMAGE));
          }
        };
  }

  /** What qits-projects composes for a desk, cut down: an env with the desk's token, the limits. */
  private static DeskEntry entry(String projectId, DesiredState desired, String hash) {
    Map<String, String> env = new LinkedHashMap<>();
    env.put("TZ", "UTC");
    env.put("QITS_TOKEN", "qits_tok_DESK");
    return new DeskEntry(
        projectId,
        desired,
        new DeskSpec(DESK_IMAGE, env, "1000", "4g", "4g", 512L, "2", 800),
        hash);
  }

  /** {@code qits-projects-desk-runner-desk-<projectId>-r1}: a desk's container and volume. */
  private static String desk(String projectId) {
    return "qits-projects-desk-runner-desk-" + projectId + "-r1";
  }

  /** The project's desk container on the node, as docker's inspect answers it from now on. */
  private void seen(String projectId, String status, String specHash) throws Exception {
    seen(projectId, status, specHash, OWN_MOUNTS);
  }

  /** The same, run with the node volumes {@code mounts} hashes (null: no mounts label at all). */
  private void seen(String projectId, String status, String specHash, String mounts)
      throws Exception {
    Map<String, String> labels = new HashMap<>();
    labels.put("qits.projects.desk.runner", RUNNER_ID);
    labels.put("qits.projects.desk.runner.project", projectId);
    labels.put("qits.projects.desk.runner.spec-hash", specHash);
    if (mounts != null) {
      labels.put("qits.projects.desk.runner.mounts", mounts);
    }
    docker.container(desk(projectId), labels);
    docker.answerFor(
        "inspect",
        desk(projectId),
        0,
        "c-"
            + projectId
            + "|"
            + status
            + "|0|"
            + projectId
            + "|"
            + specHash
            + "|"
            + (mounts == null ? "<no value>" : mounts)
            + "\n",
        "");
  }

  /** The platform host's shared volumes on the node, unlabelled, each answering its inspect. */
  private void sharedVolumes() throws Exception {
    extraEnv.putAll(SHARED_ENV);
    for (String shared : List.of(SHARED_CLAUDE, SHARED_M2, SHARED_PNPM)) {
      docker.volume(shared, Map.of());
      docker.answerFor("volume-inspect", shared, 0, shared + "\n", "");
    }
  }

  /** The mounts label of a desk run with the shared volumes. */
  private static String sharedMounts() throws Exception {
    return new NodeVolumes(
            IDENTITY, RUNNER_ID, ExternalVolumes.parse(IDENTITY, SHARED_ENV::get))
        .mountsHash();
  }

  private List<List<String>> runsOf(String name) {
    return docker.calls("run").stream().filter(c -> c.contains(name)).toList();
  }

  private List<String> runOf(String name) {
    List<List<String>> runs = runsOf(name);
    assertFalse(runs.isEmpty(), () -> "no run of " + name + ": " + docker.calls());
    return runs.getFirst();
  }

  private boolean touched(String verb, String name) {
    return docker.calls(verb).stream().anyMatch(c -> c.contains(name));
  }

  private void awaitCall(Predicate<List<List<String>>> condition) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
    while (!condition.test(docker.calls()) && System.nanoTime() < deadline) {
      Thread.sleep(20);
    }
    assertTrue(condition.test(docker.calls()), () -> "docker calls: " + docker.calls());
  }

  private static boolean has(List<List<String>> calls, List<String> call) {
    return calls.stream().map(FakeDocker::withoutConfig).anyMatch(call::equals);
  }

  /**
   * The fake's state, read while the runner's passes run beside the test: housekeeping may remove
   * a volume between the fake listing it and reading its labels file. A read that loses that race
   * is read again, until the state holds still long enough to be read whole.
   */
  private Map<String, Map<String, String>> volumes() {
    return settled(docker::volumes);
  }

  /** The fake's containers, read the same way. */
  private Map<String, Map<String, String>> containers() {
    return settled(docker::containers);
  }

  private static <T> T settled(Supplier<T> read) {
    UncheckedIOException last = null;
    for (int attempt = 0; attempt < 200; attempt++) {
      try {
        return read.get();
      } catch (UncheckedIOException vanished) {
        last = vanished;
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
      }
    }
    throw last;
  }

  /** A probe holds or it does not; one that threw mid-read is "not yet", never an escape. */
  private static boolean holds(BooleanSupplier condition) {
    try {
      return condition.getAsBoolean();
    } catch (RuntimeException notYet) {
      return false;
    }
  }

  private static void awaitTrue(BooleanSupplier condition) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
    while (!holds(condition) && System.nanoTime() < deadline) {
      Thread.sleep(20);
    }
    assertTrue(condition.getAsBoolean(), "the condition never held");
  }

  // ---- the runner's own container, for a successor -------------------------------------------------

  private static final String NEXT = "2026.999.1";
  private static final String IMAGE = "registry.example:5000/qits/qits-projects-desk-runner:" + NEXT;
  private static final String DIGEST =
      "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

  /**
   * The runner's own container as {@code docker inspect} answers it for a successor (what the
   * install script started), its old image's config, and the pulled image's digest.
   */
  private void runnerContainer() throws Exception {
    runnerContainer(List.of());
  }

  /** The same, the container having been given {@code extra} besides the install line's four. */
  private void runnerContainer(List<String> extra) throws Exception {
    List<String> env =
        new java.util.ArrayList<>(
            List.of(
                "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                "QITS_PROJECTS_DESK_RUNNER_URL=https://projects.qits.example.eu",
                "QITS_PROJECTS_DESK_RUNNER_ID=r1",
                "QITS_PROJECTS_DESK_RUNNER_SLOTS=2",
                "QITS_PROJECTS_DESK_RUNNER_REGISTRATION_TOKEN=qits_tok_SPENT"));
    env.addAll(extra);
    docker.answerFor(
        "inspect",
        SELF,
        0,
        new JsonArray()
                .add(
                    new JsonObject()
                        .put("Id", SELF + "0".repeat(52))
                        .put("Image", "sha256:old")
                        .put(
                            "Config",
                            new JsonObject()
                                .put("Env", env)
                                .put(
                                    "Labels",
                                    Map.of(
                                        "qits.projects.desk.runner.process", "r1",
                                        "qits.projects.desk.runner.version",
                                            DeskRunnerBinary.VERSION)))
                        .put(
                            "HostConfig",
                            new JsonObject()
                                .put(
                                    "Binds",
                                    List.of(
                                        "/var/run/docker.sock:/var/run/docker.sock",
                                        "qits-projects-desk-runner-state-r1:/var/lib/qits-projects-desk-runner"))
                                .put(
                                    "RestartPolicy",
                                    Map.of("Name", "unless-stopped", "MaximumRetryCount", 0))
                                .put("NetworkMode", "default")))
                .encode()
            + "\n",
        "");
    docker.answerFor(
        "image-inspect",
        "sha256:old",
        0,
        new JsonArray()
                .add(
                    new JsonObject()
                        .put("Id", "sha256:old")
                        .put(
                            "Config",
                            new JsonObject()
                                .put(
                                    "Env",
                                    List.of(
                                        "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"))))
                .encode()
            + "\n",
        "");
    docker.answerFor(
        "image-inspect",
        IMAGE,
        0,
        "[\"registry.example:5000/qits/qits-projects-desk-runner@" + DIGEST + "\"]\n",
        "");
  }
}
