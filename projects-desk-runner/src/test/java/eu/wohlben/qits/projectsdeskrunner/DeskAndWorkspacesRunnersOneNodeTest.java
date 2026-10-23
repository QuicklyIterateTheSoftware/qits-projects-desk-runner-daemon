package eu.wohlben.qits.projectsdeskrunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.containers.driver.ContainersDriver;
import eu.wohlben.qits.containers.driver.spec.ContainerSpec;
import eu.wohlben.qits.containers.driver.spec.LifecyclePolicy;
import eu.wohlben.qits.containers.driver.spec.VolumeSpec;
import eu.wohlben.qits.projectsdeskrunner.protocol.DeskEntry;
import eu.wohlben.qits.projectsdeskrunner.protocol.DeskRunnerBinary;
import eu.wohlben.qits.projectsdeskrunner.protocol.DeskRunnerCodec;
import eu.wohlben.qits.projectsdeskrunner.protocol.DeskRunnerMessage;
import eu.wohlben.qits.projectsdeskrunner.protocol.DeskSpec;
import eu.wohlben.qits.projectsdeskrunner.protocol.DesiredState;
import eu.wohlben.qits.projectsdeskrunner.protocol.Estate;
import eu.wohlben.qits.projectsdeskrunner.protocol.Take;
import eu.wohlben.qits.runner.protocol.Ack;
import eu.wohlben.qits.runner.protocol.Backlog;
import eu.wohlben.qits.runner.protocol.DomainCodec;
import eu.wohlben.qits.runner.protocol.Hello;
import eu.wohlben.qits.runner.protocol.Reserve;
import eu.wohlben.qits.runner.protocol.RunnerCapabilities;
import eu.wohlben.qits.runner.protocol.RunnerCodec;
import eu.wohlben.qits.runner.protocol.RunnerVocabulary;
import eu.wohlben.qits.runner.toolkit.Decommission;
import eu.wohlben.qits.runner.toolkit.HostFacts;
import eu.wohlben.qits.runner.toolkit.Housekeeping;
import eu.wohlben.qits.runner.toolkit.HousekeepingContext;
import eu.wohlben.qits.runner.toolkit.HousekeepingPass;
import eu.wohlben.qits.runner.toolkit.KnownRunnerNamespaces;
import eu.wohlben.qits.runner.toolkit.RunnerDomain;
import eu.wohlben.qits.runner.toolkit.RunnerExit;
import eu.wohlben.qits.runner.toolkit.RunnerIdentity;
import eu.wohlben.qits.runner.toolkit.RunnerRuntime;
import eu.wohlben.qits.runner.toolkit.RunnerSession;
import eu.wohlben.qits.runner.toolkit.RunnerSettings;
import eu.wohlben.qits.runner.toolkit.SelfUpdate;
import eu.wohlben.qits.runner.toolkit.Telemetry;
import eu.wohlben.qits.runner.toolkit.testing.FakeDocker;
import eu.wohlben.qits.runner.toolkit.testing.FakeRunnerHost;
import io.vertx.core.Vertx;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * <b>A front-desk runner and a workspaces runner on one node never touch each other</b>, the
 * dossier's two-runners case after the toolkit's own {@code TwoRunnersOneNodeTest}. Both share ONE
 * {@link FakeDocker} — one node, one set of containers and volumes — each through a docker binary of
 * its own ({@link FakeDocker#binaryFor}), so every argv is attributed to the runner that issued it.
 *
 * <p>The front desk is the real {@link DeskRunnerDomain}. The workspaces tenant is built in this
 * suite on a {@link RunnerIdentity} with {@link KnownRunnerNamespaces#WORKSPACES}' roots (this
 * repository does not depend on the workspaces runner) and a domain that reaps as hard as a
 * workspaces runner may: every own container outside its keep set at session start, every own volume
 * but its workload's at every housekeeping pass. The two runner ids share their first eight
 * characters on purpose: every derived name carries {@code <id8>}, so only the roots keep them apart.
 */
@EnabledOnOs(OS.LINUX)
class DeskAndWorkspacesRunnersOneNodeTest {

  private static final RunnerIdentity DESK = DeskRunnerIdentity.IDENTITY;

  /** A workspaces runner's identity, from the toolkit's roots for that kind. */
  private static final RunnerIdentity WORKSPACES =
      new RunnerIdentity(
          KnownRunnerNamespaces.WORKSPACES.kind(),
          KnownRunnerNamespaces.WORKSPACES.envPrefix(),
          KnownRunnerNamespaces.WORKSPACES.labelRoot(),
          KnownRunnerNamespaces.WORKSPACES.namePrefix(),
          "qits/qits-workspaces-runner",
          "/var/lib/qits-workspaces-runner",
          "/workspaces/api/runners/{id}/register",
          "qits-workspaces-runner",
          DeskRunnerBinary.VERSION,
          1,
          new RunnerVocabulary("heldWorkspaces", "adoptedWorkspaces"),
          "workspace");

  private static final String DESK_ID = "1a2b3c4d-0000-4000-8000-00000000d001";
  private static final String WS_ID = "1a2b3c4d-0000-4000-8000-00000000a001";
  private static final String ID8 = "1a2b3c4d";

  private static final String DESK_IMAGE = "registry.example:5000/qits/project-agent:2026.1008.1";
  private static final String P1 = "8273c743-e0bb-4d93-90d4-391aa10d9151";
  private static final String P2 = "5ca0e7e9-7dd8-4128-bbdd-58aa40d9b397";
  private static final Duration DEADLINE = Duration.ofSeconds(10);

  private final Vertx vertx = Vertx.vertx();
  private FakeDocker node;
  private FakeRunnerHost deskHost;
  private FakeRunnerHost wsHost;
  private DeskRunnerDomain deskDomain;
  private final Map<String, RunnerRuntime<DeskRunnerMessage>> runtimes = new ConcurrentHashMap<>();
  private final Map<String, CompletableFuture<Integer>> exits = new ConcurrentHashMap<>();

  @TempDir Path nodeDir;
  @TempDir Path deskState;
  @TempDir Path wsState;

  @BeforeEach
  void setUp() throws Exception {
    node = new FakeDocker(nodeDir);
    node.answerFor("run", "status", 0, "{\"loggedIn\": true}\n", "");
    deskHost = new FakeRunnerHost(vertx, DESK, codec(DESK));
    wsHost = new FakeRunnerHost(vertx, WORKSPACES, codec(WORKSPACES));
  }

  @AfterEach
  void tearDown() throws Exception {
    for (Map.Entry<String, RunnerRuntime<DeskRunnerMessage>> r : runtimes.entrySet()) {
      r.getValue().stop(RunnerExit.OK);
      exits.get(r.getKey()).get(10, TimeUnit.SECONDS);
    }
    if (deskDomain != null) {
      deskDomain.close();
    }
    deskHost.close();
    wsHost.close();
    vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
  }

  @Test
  void neitherRunnerListsNamesOrRemovesTheOthersContainersOrVolumes() throws Exception {
    // The front desk's: a running desk of P1 and its volume.
    String desk1 = DESK.nodeName(DESK_ID, "desk-" + P1);
    String mounts = new NodeVolumes(DESK, DESK_ID).mountsHash();
    String desk2 = DESK.nodeName(DESK_ID, "desk-" + P2);
    node.container(
        desk1,
        Map.of(
            DESK.ownerLabel(), DESK_ID,
            DESK.workLabel(), P1,
            DESK.driverNamespace() + "spec-hash", "h1",
            DESK.driverNamespace() + "mounts", mounts));
    node.volume(desk1, Map.of(DESK.ownerLabel(), DESK_ID, DESK.workLabel(), P1));
    node.answerFor("inspect", desk1, 0, "c1|running|0|" + P1 + "|h1|" + mounts + "\n", "");
    // The workspaces runner's: a workspace left from an earlier session, and its volume.
    String leftover = WORKSPACES.nodeName(WS_ID, "ws-9");
    node.container(leftover, Map.of(WORKSPACES.ownerLabel(), WS_ID, WORKSPACES.workLabel(), "9"));
    node.volume(leftover, Map.of(WORKSPACES.ownerLabel(), WS_ID, WORKSPACES.workLabel(), "9"));

    deskHost.script =
        (h, message) -> {
          if (message instanceof Hello) {
            h.send(new Ack(DESK.capabilityVersion(), 2));
            h.send(new Estate(List.of(entry(P1, "h1"), entry(P2, "h2")), DESK_IMAGE));
          }
        };
    wsHost.script =
        (h, message) -> {
          if (message instanceof Hello) {
            h.send(new Ack(WORKSPACES.capabilityVersion(), 2));
            h.send(new Backlog(1));
          } else if (message instanceof Reserve && h.all(Reserve.class).size() == 1) {
            DeskEntry e = entry("w1", "hw");
            h.send(new Take(e.projectId(), e.desired(), e.spec(), e.specHash()));
          }
        };
    Tenant tenant = new Tenant();
    start("desk", DESK, DESK_ID, deskState, deskHost, null, "d1d1d1d1d1d1");
    start("ws", WORKSPACES, WS_ID, wsState, wsHost, tenant, "a1a1a1a1a1a1");

    // Both converge: the desk of P2 runs, the workspace w1 runs, the workspace leftover is reaped.
    awaitTrue(() -> containers().containsKey(desk2));
    node.answerFor("inspect", desk2, 0, "c2|running|0|" + P2 + "|h2|" + mounts + "\n", "");
    awaitTrue(() -> containers().containsKey(WORKSPACES.nodeName(WS_ID, "item-w1")));
    awaitTrue(() -> !containers().containsKey(leftover));
    awaitTrue(() -> !volumes().containsKey(leftover));

    // The desk of P1 leaves the estate: its container goes at once, its volume at housekeeping.
    deskHost.send(new Estate(List.of(entry(P2, "h2")), DESK_IMAGE));
    awaitTrue(() -> !containers().containsKey(desk1));
    awaitTrue(() -> !volumes().containsKey(desk1));
    // Several passes of both runners' housekeeping.
    Thread.sleep(800);

    assertEquals(
        Set.of(desk2, WORKSPACES.nodeName(WS_ID, "item-w1")),
        containers().keySet(),
        "each runner's own workload, nothing else");
    assertEquals(
        Set.of(
            desk2,
            DESK.nodeVolume(DESK_ID, "dot-claude"),
            DESK.nodeVolume(DESK_ID, "m2"),
            DESK.nodeVolume(DESK_ID, "pnpm"),
            WORKSPACES.nodeVolume(WS_ID, "data-w1")),
        volumes().keySet());
    for (String name : containers().keySet()) {
      assertTrue(name.endsWith("-" + ID8), "both ids' first eight characters: " + name);
    }

    for (List<String> call : node.callsBy("desk")) {
      String argv = String.join(" ", call);
      assertFalse(argv.contains("qits-workspaces-runner"), "desk named a workspaces thing: " + argv);
      assertFalse(argv.contains("qits.workspaces.runner"), "desk read a workspaces label: " + argv);
    }
    for (List<String> call : node.callsBy("ws")) {
      String argv = String.join(" ", call);
      assertFalse(argv.contains("qits-projects-desk-runner"), "ws named a desk thing: " + argv);
      assertFalse(argv.contains("qits.projects.desk.runner"), "ws read a desk label: " + argv);
    }
    assertTrue(tenant.reaped.get() >= 2, "the workspaces tenant did reap, twice at least");
  }

  // ---- the two runners ----------------------------------------------------------------------------

  private void start(
      String caller,
      RunnerIdentity identity,
      String id,
      Path state,
      FakeRunnerHost host,
      Tenant tenant,
      String self)
      throws Exception {
    Map<String, String> env = new HashMap<>();
    env.put(identity.env(RunnerSettings.URL), host.base());
    env.put(identity.env(RunnerSettings.ID), id);
    env.put(identity.env(RunnerSettings.REGISTRATION_TOKEN), "one-time-token");
    env.put(identity.env(RunnerSettings.STATE_DIR), state.toString());
    env.put(identity.env(RunnerSettings.SLOTS), "2");
    env.put(identity.env(RunnerSettings.DOCKER_BINARY), node.binaryFor(caller));
    env.put(identity.env(RunnerSettings.DOCKER_TIMEOUT), "10");
    RunnerSettings settings =
        RunnerSettings.parse(identity, env::get)
            .withTiming(new RunnerSettings.Timing(10_000, 50, 200, 5_000, 300_000, 3));
    RunnerDomain<DeskRunnerMessage> domain;
    if (tenant == null) {
      deskDomain =
          new DeskRunnerDomain(
              settings,
              new DeskRunnerDomain.Timing(
                  Duration.ofMillis(600),
                  Duration.ofMillis(50),
                  Duration.ofMillis(200),
                  Duration.ofSeconds(60),
                  Duration.ofSeconds(60)),
              Clock.systemUTC());
      domain = deskDomain;
    } else {
      domain = tenant;
    }
    RunnerRuntime<DeskRunnerMessage> runtime =
        new RunnerRuntime<>(
            vertx,
            identity,
            settings,
            domain,
            new HostFacts(true, "amd64", "linux"),
            Telemetry.off(),
            new RunnerRuntime.NodeOptions(
                Optional.of(self),
                new SelfUpdate.Settings(100, 400, 3_000, 400, 50),
                new Decommission.Settings(200, 20, 2),
                new Housekeeping.Settings(200),
                Clock.systemUTC()));
    runtimes.put(caller, runtime);
    exits.put(caller, CompletableFuture.supplyAsync(runtime::run));
  }

  private static RunnerCodec<DeskRunnerMessage> codec(RunnerIdentity identity) {
    return new RunnerCodec<>(identity.vocabulary(), new DeskRunnerCodec());
  }

  private static DeskEntry entry(String projectId, String hash) {
    return new DeskEntry(
        projectId,
        DesiredState.RUNNING,
        new DeskSpec(DESK_IMAGE, Map.of(), "1000", null, null, null, null, null),
        hash);
  }

  /**
   * The fake's state, read while the runner's passes run beside the test: housekeeping may remove
   * a volume between the fake listing it and reading its labels file. A read that loses that race
   * is read again, until the state holds still long enough to be read whole.
   */
  private Map<String, Map<String, String>> volumes() {
    return settled(node::volumes);
  }

  /** The fake's containers, read the same way. */
  private Map<String, Map<String, String>> containers() {
    return settled(node::containers);
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

  /**
   * The workspaces tenant: reconciles its own containers against its keep set at session start,
   * launches each take as a container with a volume through {@code own().labels(workId)}, and
   * removes every own volume but its workloads' at each housekeeping pass.
   */
  private static final class Tenant implements RunnerDomain<DeskRunnerMessage> {

    final Set<String> kept = ConcurrentHashMap.newKeySet();
    final AtomicInteger reaped = new AtomicInteger();

    @Override
    public DomainCodec<DeskRunnerMessage> codec() {
      return new DeskRunnerCodec();
    }

    @Override
    public RunnerCapabilities capabilities(HostFacts host) {
      return new RunnerCapabilities(true, "amd64", "linux", Map.of());
    }

    @Override
    public void onSessionStart(RunnerSession session, List<String> carried) {
      session.own().reconcile(Set.copyOf(carried));
    }

    @Override
    public void onAck(Ack ack, RunnerSession session) {}

    @Override
    public void onMessage(DeskRunnerMessage message, RunnerSession session) {
      if (!(message instanceof Take take)) {
        return;
      }
      String workId = take.projectId();
      Map<String, String> labels = session.own().labels(workId);
      ContainersDriver driver = session.driver();
      String volume = session.identity().nodeVolume(WS_ID, "data-" + workId);
      kept.add(volume);
      driver.ensureVolume(new VolumeSpec(volume), labels, DEADLINE);
      ContainersDriver.Started started =
          driver.run(
              ContainerSpec.builder("registry.example:5000/qits/workspace:1")
                  .mount(volume, "/workspace")
                  .build(),
              session.identity().nodeName(WS_ID, "item-" + workId),
              labels,
              LifecyclePolicy.explicitLifetime(),
              DEADLINE);
      if (started.started()) {
        session.taken(workId);
      }
    }

    @Override
    public boolean quiescent() {
      return true;
    }

    @Override
    public void onDecommission(RunnerSession session) {}

    @Override
    public List<HousekeepingPass> housekeeping() {
      return List.of(
          new HousekeepingPass() {
            @Override
            public String name() {
              return "workspaces-tenant";
            }

            @Override
            public void run(HousekeepingContext context) {
              for (String volume : context.own().volumes()) {
                if (!kept.contains(volume)) {
                  context.driver().removeVolume(volume, context.deadline());
                }
              }
              reaped.incrementAndGet();
            }
          });
    }
  }
}
