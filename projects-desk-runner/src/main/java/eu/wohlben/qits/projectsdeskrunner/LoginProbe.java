package eu.wohlben.qits.projectsdeskrunner;

import eu.wohlben.qits.containers.driver.ContainersDriver;
import eu.wohlben.qits.containers.driver.spec.ContainersIdentifiers;
import eu.wohlben.qits.projectsdeskrunner.protocol.LoginPresence;
import eu.wohlben.qits.runner.toolkit.LifecycleArgv;
import eu.wohlben.qits.runner.toolkit.RunnerDocker;
import eu.wohlben.qits.runner.toolkit.RunnerIdentity;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * Whether Claude Code is logged in on this node — presence only. A copy of
 * qits-workspaces-runner-daemon's {@code LoginProbe} pointed at this runner's {@code dot-claude}
 * ({@link NodeVolumes#dotClaude}) and the pinned project-agent image: {@code claude auth status}
 * with {@code HOME=/claude-home} and {@code CLAUDE_CONFIG_DIR=/claude-home/.claude} — exactly where
 * every desk container's own {@code CLAUDE_CONFIG_DIR} points — read by {@code loggedIn} in the JSON
 * it prints. The front desk runs Claude only, so there is no Kimi probe.
 *
 * <p><b>Nothing a probe prints is logged, kept or sent</b> beyond the presence: {@code auth status}
 * names the account, and the platform never sees or moves a login. A probe that could not run — no
 * image here yet, no agent home, docker refusing, the {@link #DEADLINE} passing — answers
 * <b>nothing</b>: the front-desk {@code loginState} has no UNKNOWN, and an operator told "not logged
 * in" would log in again to fix a docker fault.
 *
 * <p>Runs as the desks' own user when one is known (the latest {@code DeskSpec.user}): a login
 * read as root would read PRESENT here and still be unreadable by the desk that uses it. Each probe
 * container is {@code --rm} and carries the runner's ownership label, so one that outlives its
 * deadline still goes by itself — and, at the latest, with the runner at decommission.
 */
final class LoginProbe {

  private static final Logger LOG = Logger.getLogger(LoginProbe.class);

  /** Each probe's: a {@code claude auth status} that has not answered by then is not answering. */
  static final Duration DEADLINE = Duration.ofSeconds(30);

  /** Where the agent home is mounted, as every desk container mounts it. */
  static final String CLAUDE_HOME = NodeVolumes.CLAUDE_HOME_TARGET;

  /** Claude Code's config directory inside the agent home, as the desk spec sets it. */
  static final String CLAUDE_CONFIG_DIR = CLAUDE_HOME + "/.claude";

  private final RunnerIdentity identity;
  private final String runnerId;
  private final RunnerDocker docker;
  private final NodeVolumes volumes;

  /**
   * @param docker the runner's docker under the probe's own {@link #DEADLINE}
   */
  LoginProbe(RunnerIdentity identity, String runnerId, RunnerDocker docker, NodeVolumes volumes) {
    this.identity = identity;
    this.runnerId = runnerId;
    this.docker = docker;
    this.volumes = volumes;
  }

  /**
   * The probe against {@code image} (null before the first {@code estate}) as {@code user} (null:
   * the image's own); empty when it could not run. Never throws.
   */
  synchronized Optional<LoginPresence> probe(ContainersDriver driver, String image, String user) {
    try {
      if (image == null || image.isBlank() || !NodeImages.present(docker, image)) {
        LOG.infof("projects-desk-runner's login probe cannot run: the desk image is not here yet");
        return Optional.empty();
      }
      ContainersDriver.OpResult home = volumes.ensureDotClaude(driver, docker.deadline());
      if (!home.ok()) {
        LOG.warnf("projects-desk-runner's login probe cannot run: no agent home volume");
        return Optional.empty();
      }
      Optional<LoginPresence> claude = claude(docker.run(argv(image, user)));
      LOG.infof(
          "projects-desk-runner's login probe: claude %s",
          claude.map(Enum::name).orElse("could not tell"));
      return claude;
    } catch (RuntimeException e) {
      LOG.warnf("projects-desk-runner's login probe could not run: %s", e.getMessage());
      return Optional.empty();
    }
  }

  /**
   * {@code docker run --rm --label <owner> [--user <user>] --entrypoint claude -v
   * <dot-claude>:/claude-home -e HOME=/claude-home -e CLAUDE_CONFIG_DIR=/claude-home/.claude
   * <image> auth status}. The user passes the driver's own belt, as the desk's {@code run} does.
   */
  List<String> argv(String image, String user) {
    List<String> argv = new ArrayList<>();
    argv.add(docker.binary());
    argv.add("run");
    argv.add("--rm");
    argv.add("--label");
    argv.add(identity.ownerLabel() + "=" + LifecycleArgv.requireRunnerId(runnerId));
    if (user != null && !user.isBlank()) {
      argv.add("--user");
      argv.add(ContainersIdentifiers.requireUser(user));
    }
    argv.add("--entrypoint");
    argv.add("claude");
    argv.add("-v");
    argv.add(volumes.dotClaude() + ":" + CLAUDE_HOME);
    argv.add("-e");
    argv.add("HOME=" + CLAUDE_HOME);
    argv.add("-e");
    argv.add("CLAUDE_CONFIG_DIR=" + CLAUDE_CONFIG_DIR);
    argv.add(LifecycleArgv.requireImage(image));
    argv.add("auth");
    argv.add("status");
    return List.copyOf(argv);
  }

  /**
   * The workspaces runner's reading: the JSON's {@code loggedIn} wins whatever the exit code (a
   * signed-out CLI exits non-zero and still prints it); without it, exit 0 is a login, a run docker
   * could not make (125), whose command could not start (126, 127) or that timed out could not
   * tell, and any other non-zero exit is the CLI saying no.
   */
  static Optional<LoginPresence> claude(RunnerDocker.Result result) {
    String out = result.stdout() == null ? "" : result.stdout();
    if (!result.timedOut() && out.contains("\"loggedIn\"")) {
      return Optional.of(
          out.replace(" ", "").contains("\"loggedIn\":true")
              ? LoginPresence.PRESENT
              : LoginPresence.ABSENT);
    }
    if (result.timedOut()) {
      return Optional.empty();
    }
    return switch (result.exitCode()) {
      case 0 -> Optional.of(LoginPresence.PRESENT);
      case -1, 125, 126, 127 -> Optional.empty();
      default -> Optional.of(LoginPresence.ABSENT);
    };
  }
}
