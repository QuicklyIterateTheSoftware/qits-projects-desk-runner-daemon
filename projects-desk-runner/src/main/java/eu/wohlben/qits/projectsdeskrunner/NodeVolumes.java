package eu.wohlben.qits.projectsdeskrunner;

import eu.wohlben.qits.containers.driver.ContainersDriver;
import eu.wohlben.qits.containers.driver.spec.VolumeSpec;
import eu.wohlben.qits.runner.toolkit.OwnResources;
import eu.wohlben.qits.runner.toolkit.RunnerIdentity;
import java.time.Duration;
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

  private final RunnerIdentity identity;
  private final String runnerId;
  private final DeskNames names;

  NodeVolumes(RunnerIdentity identity, String runnerId) {
    this.identity = identity;
    this.runnerId = runnerId;
    this.names = new DeskNames(identity, runnerId);
  }

  /** {@code qits-projects-desk-runner-desk-<projectId>-<id8>}: the desk's {@code /workspace}. */
  String desk(String projectId) {
    return names.deskVolume(projectId);
  }

  /** {@code qits-projects-desk-runner-dot-claude-<id8>}: the node's agent home and login. */
  String dotClaude() {
    return identity.nodeVolume(runnerId, DOT_CLAUDE);
  }

  /** {@code qits-projects-desk-runner-m2-<id8>}: the node's maven cache. */
  String m2() {
    return identity.nodeVolume(runnerId, M2);
  }

  /** {@code qits-projects-desk-runner-pnpm-<id8>}: the node's pnpm store. */
  String pnpm() {
    return identity.nodeVolume(runnerId, PNPM);
  }

  /** The node's three, in the order a desk mounts them. */
  List<String> node() {
    return List.of(dotClaude(), m2(), pnpm());
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

  /** The agent home, made labelled when it is not there yet. */
  ContainersDriver.OpResult ensureDotClaude(ContainersDriver driver, Duration deadline) {
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
