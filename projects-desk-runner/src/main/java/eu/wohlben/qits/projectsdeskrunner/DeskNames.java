package eu.wohlben.qits.projectsdeskrunner;

import eu.wohlben.qits.runner.toolkit.RunnerIdentity;
import java.util.Optional;

/**
 * Every name the front-desk runner gives a thing on its node, derived from its {@link
 * RunnerIdentity} and never from a frame — so no frame can point the runner at a container or a
 * volume it does not own (qits-workspaces-runner-daemon's {@code WorkspaceNames}).
 *
 * <ul>
 *   <li>a desk's container and its {@code /workspace} volume: {@code nodeName(id,
 *       "desk-<projectId>")} = {@code qits-projects-desk-runner-desk-<projectId>-<id8>}, keyed on
 *       the PROJECT id;
 *   <li>the health check's throwaway container: {@code qits-projects-desk-runner-health-check-<id8>}.
 * </ul>
 *
 * <p>The node volumes ({@code dot-claude}, {@code m2}, {@code pnpm}) are {@link NodeVolumes}'.
 */
final class DeskNames {

  /** The purpose segment of a desk's container and volume, before its project id. */
  static final String DESK = "desk";

  /** The purpose segment of the health check's container. */
  static final String HEALTH_CHECK = "health-check";

  private final RunnerIdentity identity;
  private final String runnerId;

  DeskNames(RunnerIdentity identity, String runnerId) {
    this.identity = identity;
    this.runnerId = runnerId;
  }

  /**
   * {@code qits-projects-desk-runner-desk-<projectId>-<id8>}: the project's desk container.
   *
   * @throws IllegalArgumentException when the project id cannot be part of a name
   */
  String container(String projectId) {
    return identity.nodeName(runnerId, DESK + "-" + projectId);
  }

  /** The project's {@code /workspace} volume: the same spelling as {@link #container}. */
  String deskVolume(String projectId) {
    return identity.nodeVolume(runnerId, DESK + "-" + projectId);
  }

  /** {@code qits-projects-desk-runner-health-check-<id8>}. */
  String healthCheck() {
    return identity.nodeName(runnerId, HEALTH_CHECK);
  }

  /**
   * The project whose desk container or volume {@code name} is, or empty when it is no desk's of
   * this runner: everything between the desk prefix and the last {@code -} is read back as the
   * project id (a UUID carries dashes of its own), then the name is derived again and compared.
   */
  Optional<String> projectOf(String name) {
    String prefix = identity.namePrefix() + "-" + DESK + "-";
    if (name == null || !name.startsWith(prefix)) {
      return Optional.empty();
    }
    int dash = name.lastIndexOf('-');
    if (dash <= prefix.length()) {
      return Optional.empty();
    }
    String projectId = name.substring(prefix.length(), dash);
    try {
      return deskVolume(projectId).equals(name) ? Optional.of(projectId) : Optional.empty();
    } catch (IllegalArgumentException notAProject) {
      return Optional.empty();
    }
  }
}
