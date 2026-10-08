package eu.wohlben.qits.projectsdeskrunner;

import eu.wohlben.qits.containers.driver.ContainersDriver;
import eu.wohlben.qits.containers.driver.spec.VolumeSpec;
import eu.wohlben.qits.runner.toolkit.OwnResources;
import eu.wohlben.qits.runner.toolkit.RunnerDocker;
import eu.wohlben.qits.runner.toolkit.RunnerIdentity;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The four volumes a desk container mounts, named from the runner's {@link RunnerIdentity} and
 * never from a frame (qits-workspaces-runner-daemon's {@code NodeVolumes}, keyed on the project):
 *
 * <ul>
 *   <li>the desk's own {@code /workspace}: {@code qits-projects-desk-runner-desk-<projectId>-<id8>},
 *       the same name as its container, labelled {@code qits.projects.desk.runner=<id>} and {@code
 *       qits.projects.desk.runner.project=<projectId>} ({@link OwnResources#labels});
 *   <li>the node's {@code dot-claude} ({@code /claude-home}), {@code m2} ({@code /caches/m2}) and
 *       {@code pnpm} ({@code /caches/pnpm}): {@code qits-projects-desk-runner-<purpose>-<id8>},
 *       shared by this runner's desks only and labelled {@code qits.projects.desk.runner=<id>}
 *       alone — they belong to no project, so no estate ever removes them; only the decommission
 *       does, with the node's agent login on the first of them.
 * </ul>
 *
 * <p><b>Created labelled before first use</b>, and every use asks again: {@code volume create} is
 * docker's own no-op for a name that exists. A {@code docker run -v <name>:…} against a volume that
 * is not there yet creates it <em>unlabelled</em>, and an unlabelled volume is one the
 * decommission never finds — so the runner makes the agent home before the operator's login
 * command can.
 *
 * <p><b>Or the operator's</b> ({@link ExternalVolumes}): each of the three node volumes may be
 * replaced by an external one the install line names — on the platform host, the shared volumes
 * every other container there mounts. An external volume is mounted in the same place and is
 * otherwise not the runner's: it is never created ({@link #owned} leaves it out), never labelled, so
 * neither {@link OwnResources}, {@link DeskVolumesPass} nor the decommission helper — which all
 * select by the runner's label — can ever reach it. Before a desk runs, {@link #missing} asks
 * docker that each external one exists, because a {@code -v} naming a volume that is not there
 * would quietly create it empty.
 */
final class NodeVolumes {

  static final String DOT_CLAUDE = "dot-claude";
  static final String M2 = "m2";
  static final String PNPM = "pnpm";

  /** Where each volume is mounted in a desk container. */
  static final String WORKSPACE_TARGET = "/workspace";

  static final String CLAUDE_HOME_TARGET = "/claude-home";
  static final String M2_TARGET = "/caches/m2";
  static final String PNPM_TARGET = "/caches/pnpm";

  /** An external node volume and the variable that named it. */
  record External(String name, String variable) {}

  private final RunnerIdentity identity;
  private final String runnerId;
  private final DeskNames names;
  private final ExternalVolumes external;

  NodeVolumes(RunnerIdentity identity, String runnerId) {
    this(identity, runnerId, ExternalVolumes.NONE);
  }

  NodeVolumes(RunnerIdentity identity, String runnerId, ExternalVolumes external) {
    this.identity = identity;
    this.runnerId = runnerId;
    this.names = new DeskNames(identity, runnerId);
    this.external = external;
  }

  /** {@code qits-projects-desk-runner-desk-<projectId>-<id8>}: the desk's {@code /workspace}. */
  String desk(String projectId) {
    return names.deskVolume(projectId);
  }

  /**
   * The node's agent home and login: the external volume when one is named, else {@code
   * qits-projects-desk-runner-dot-claude-<id8>}.
   */
  String dotClaude() {
    return external.claude().orElseGet(() -> identity.nodeVolume(runnerId, DOT_CLAUDE));
  }

  /** The node's maven cache: external, else {@code qits-projects-desk-runner-m2-<id8>}. */
  String m2() {
    return external.m2().orElseGet(() -> identity.nodeVolume(runnerId, M2));
  }

  /** The node's pnpm store: external, else {@code qits-projects-desk-runner-pnpm-<id8>}. */
  String pnpm() {
    return external.pnpm().orElseGet(() -> identity.nodeVolume(runnerId, PNPM));
  }

  /** The node's three as a desk mounts them, in that order — own or external. */
  List<String> node() {
    return List.of(dotClaude(), m2(), pnpm());
  }

  /** The node volumes that are the runner's own: those no external one replaces. */
  List<String> owned() {
    List<String> own = new ArrayList<>();
    if (external.claude().isEmpty()) {
      own.add(dotClaude());
    }
    if (external.m2().isEmpty()) {
      own.add(m2());
    }
    if (external.pnpm().isEmpty()) {
      own.add(pnpm());
    }
    return List.copyOf(own);
  }

  /** The external node volumes, each with the variable that named it. */
  List<External> external() {
    List<External> named = new ArrayList<>();
    external
        .claude()
        .ifPresent(n -> named.add(new External(n, identity.env(ExternalVolumes.CLAUDE_VOLUME))));
    external.m2().ifPresent(n -> named.add(new External(n, identity.env(ExternalVolumes.M2_VOLUME))));
    external
        .pnpm()
        .ifPresent(n -> named.add(new External(n, identity.env(ExternalVolumes.PNPM_VOLUME))));
    return List.copyOf(named);
  }

  /** Whether the agent home is an external volume. */
  boolean dotClaudeExternal() {
    return external.claude().isPresent();
  }

  /**
   * {@code qits.projects.desk.runner.mounts}' value: a short hash of the node mounts a desk gets
   * now. A desk container carrying another one (or none) was run with other node volumes and is
   * recreated like one of another spec hash.
   */
  String mountsHash() {
    String mounts =
        String.join(
            "\n",
            dotClaude() + ":" + CLAUDE_HOME_TARGET,
            m2() + ":" + M2_TARGET,
            pnpm() + ":" + PNPM_TARGET);
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(mounts.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest, 0, 6);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("no SHA-256 in this JVM", e);
    }
  }

  /**
   * Why a desk cannot run yet: the first external volume docker does not have (or could not say
   * it has), as a sentence for {@code launchFailed}; empty when every one is there. Asked with a
   * {@code docker volume inspect}, never a create.
   */
  Optional<String> missing(RunnerDocker docker) {
    for (External named : external()) {
      Optional<String> why = missing(docker, named);
      if (why.isPresent()) {
        return why;
      }
    }
    return Optional.empty();
  }

  /** {@link #missing(RunnerDocker)} for the agent home alone: what the login probe needs. */
  Optional<String> dotClaudeMissing(RunnerDocker docker) {
    if (!dotClaudeExternal()) {
      return Optional.empty();
    }
    return missing(docker, new External(dotClaude(), identity.env(ExternalVolumes.CLAUDE_VOLUME)));
  }

  private static Optional<String> missing(RunnerDocker docker, External named) {
    RunnerDocker.Result answered =
        docker.run(
            List.of(
                docker.binary(),
                "volume",
                "inspect",
                "--format",
                "{{.Name}}",
                named.name()));
    if (answered.ok() && named.name().equals(answered.stdout().strip())) {
      return Optional.empty();
    }
    if (!answered.ok() && !answered.timedOut() && DeskContainers.absent(answered.detail())) {
      return Optional.of(
          "node volume " + named.name() + " named by " + named.variable() + " does not exist");
    }
    return Optional.of(
        "docker could not say whether node volume "
            + named.name()
            + " named by "
            + named.variable()
            + " exists: "
            + DeskLauncher.brief(answered.ok() ? answered.stdout() : answered.detail()));
  }

  /** The desk's own volume, labelled as its container is; idempotent. */
  ContainersDriver.OpResult ensureDesk(
      ContainersDriver driver, OwnResources own, String projectId, Duration deadline) {
    return driver.ensureVolume(new VolumeSpec(desk(projectId)), own.labels(projectId), deadline);
  }

  /** One of the node's volumes, labelled with the runner's ownership label alone; idempotent. */
  ContainersDriver.OpResult ensureNode(ContainersDriver driver, String name, Duration deadline) {
    return driver.ensureVolume(
        new VolumeSpec(name), Map.of(identity.ownerLabel(), runnerId), deadline);
  }

  /** The agent home, made labelled when it is not there yet; never called for an external one. */
  ContainersDriver.OpResult ensureDotClaude(ContainersDriver driver, Duration deadline) {
    if (dotClaudeExternal()) {
      throw new IllegalStateException("an external agent home is never the runner's to create");
    }
    return ensureNode(driver, dotClaude(), deadline);
  }

  /**
   * The project whose desk volume {@code name} is, or empty when it is no desk's: the name is read
   * back and then re-derived, so only a name this runner would itself give a desk answers.
   */
  Optional<String> projectOf(String name) {
    return names.projectOf(name);
  }
}
