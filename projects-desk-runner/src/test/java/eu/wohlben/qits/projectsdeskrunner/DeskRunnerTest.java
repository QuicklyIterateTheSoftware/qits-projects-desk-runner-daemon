package eu.wohlben.qits.projectsdeskrunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projectsdeskrunner.protocol.DeskEntry;
import eu.wohlben.qits.projectsdeskrunner.protocol.DeskRunnerBinary;
import eu.wohlben.qits.projectsdeskrunner.protocol.DeskRunnerCodec;
import eu.wohlben.qits.projectsdeskrunner.protocol.DeskRunnerMessage;
import eu.wohlben.qits.projectsdeskrunner.protocol.DeskSpec;
import eu.wohlben.qits.projectsdeskrunner.protocol.DesiredState;
import eu.wohlben.qits.projectsdeskrunner.protocol.Estate;
import eu.wohlben.qits.projectsdeskrunner.protocol.ProbeLogin;
import eu.wohlben.qits.runner.protocol.Ack;
import eu.wohlben.qits.runner.protocol.Backlog;
import eu.wohlben.qits.runner.protocol.Heartbeat;
import eu.wohlben.qits.runner.protocol.Hello;
import eu.wohlben.qits.runner.protocol.RunnerCapabilities;
import eu.wohlben.qits.runner.protocol.RunnerCodec;
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
import java.nio.file.Path;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * The skeleton's whole promise, against qits-runner-toolkit's own fixtures ({@link FakeRunnerHost},
 * {@link FakeDocker}): it registers at the projects door, says hello as the front-desk runner, and
 * — until the convergence lands — touches nothing on the node whatever the host sends it. The
 * runtime is the toolkit's {@link RunnerRuntime} with {@link DeskRunnerDomain}, exactly what {@code
 * Main} hands {@code RunnerLauncher}.
 */
@EnabledOnOs(OS.LINUX)
class DeskRunnerTest {

  private static final RunnerIdentity IDENTITY = DeskRunnerIdentity.IDENTITY;

  private static final RunnerCodec<DeskRunnerMessage> CODEC =
      new RunnerCodec<>(IDENTITY.vocabulary(), new DeskRunnerCodec());

  private static final String RUNNER_ID = "r1";

  private static final String DESK_IMAGE = "registry.example:5000/qits/project-agent:2026.1008.1";

  private final Vertx vertx = Vertx.vertx();
  private FakeRunnerHost host;
  private FakeDocker docker;
  private RunnerRuntime<DeskRunnerMessage> runner;
  private CompletableFuture<Integer> exit;

  @TempDir Path state;
  @TempDir Path dockerDir;

  @BeforeEach
  void setUp() throws Exception {
    host = new FakeRunnerHost(vertx, IDENTITY, CODEC);
    docker = new FakeDocker(dockerDir);
  }

  @AfterEach
  void tearDown() throws Exception {
    if (runner != null) {
      runner.stop(RunnerExit.OK);
      exit.get(10, TimeUnit.SECONDS);
    }
    host.close();
    vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
  }

  @Test
  void itRegistersAtTheProjectsDoorAndSaysHelloAsTheFrontDeskRunner() throws Exception {
    host.script =
        (h, message) -> {
          if (message instanceof Hello) {
            h.send(new Ack(IDENTITY.capabilityVersion(), 8));
            h.send(new Backlog(0));
          }
        };
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

  @Test
  void theSkeletonTouchesNoDeskWhateverTheHostSends() throws Exception {
    DeskSpec spec = new DeskSpec(DESK_IMAGE, Map.of(), "1000", null, null, null, null, null);
    host.script =
        (h, message) -> {
          if (message instanceof Hello) {
            h.send(new Ack(IDENTITY.capabilityVersion(), 8));
            h.send(
                new Estate(
                    List.of(new DeskEntry("p1", DesiredState.RUNNING, spec, "sha256:a")),
                    DESK_IMAGE));
            h.send(new ProbeLogin());
          }
        };
    start();
    host.await(Hello.class);
    // A heartbeat proves the session outlived the frames it was sent.
    host.await(Heartbeat.class);

    assertTrue(docker.calls("run").isEmpty(), () -> "ran: " + docker.calls("run"));
    assertTrue(docker.calls("rm").isEmpty(), () -> "removed: " + docker.calls("rm"));
    assertTrue(docker.calls("volume").isEmpty(), () -> "volumes: " + docker.calls("volume"));
  }

  private void start() throws Exception {
    Map<String, String> env = new HashMap<>();
    env.put(IDENTITY.env(RunnerSettings.URL), host.base());
    env.put(IDENTITY.env(RunnerSettings.ID), RUNNER_ID);
    env.put(IDENTITY.env(RunnerSettings.REGISTRATION_TOKEN), "token-1");
    env.put(IDENTITY.env(RunnerSettings.STATE_DIR), state.toString());
    env.put(IDENTITY.env(RunnerSettings.SLOTS), "8");
    env.put(IDENTITY.env(RunnerSettings.DOCKER_BINARY), docker.binary());
    env.put(IDENTITY.env(RunnerSettings.DOCKER_TIMEOUT), "10");
    RunnerSettings settings =
        RunnerSettings.parse(IDENTITY, env::get)
            .withTiming(new RunnerSettings.Timing(200, 50, 200, 5_000, 300_000, 5));
    runner =
        new RunnerRuntime<>(
            vertx,
            IDENTITY,
            settings,
            new DeskRunnerDomain(settings),
            new HostFacts(true, "amd64", "linux"),
            Telemetry.off(),
            new RunnerRuntime.NodeOptions(
                Optional.of("0123456789ab"),
                new SelfUpdate.Settings(100, 400, 3_000, 400, 50),
                new Decommission.Settings(200, 20, 2),
                new Housekeeping.Settings(TimeUnit.HOURS.toMillis(6)),
                Clock.systemUTC()));
    exit = CompletableFuture.supplyAsync(runner::run);
  }
}
