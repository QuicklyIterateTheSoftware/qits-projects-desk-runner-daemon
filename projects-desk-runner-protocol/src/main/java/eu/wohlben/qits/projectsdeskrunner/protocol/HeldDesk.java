package eu.wohlben.qits.projectsdeskrunner.protocol;

/**
 * One desk in an {@link Inventory}.
 *
 * @param projectId the project the desk belongs to
 * @param state what docker said of its container
 * @param specHash the spec-hash label of its container, or null when there is none (an absent
 *     desk, or a container without the label)
 * @param detail one line for a person — why it FAILED, for instance — or null
 */
public record HeldDesk(String projectId, DeskState state, String specHash, String detail) {

  public HeldDesk {
    if (projectId == null || projectId.isBlank()) {
      throw new IllegalArgumentException("a held desk names no project");
    }
    if (state == null) {
      throw new IllegalArgumentException("a held desk has no state");
    }
  }
}
