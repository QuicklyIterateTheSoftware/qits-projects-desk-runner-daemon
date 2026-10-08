package eu.wohlben.qits.projectsdeskrunner.protocol;

/**
 * One desk qits-projects assigns this runner: an {@link Estate} entry, and the body of a {@link
 * Take}.
 *
 * @param projectId the project the desk belongs to; every name and label of the desk is keyed on it
 * @param desired whether its container should run
 * @param spec what the container is
 * @param specHash the server's hash of the canonical spec. The runner labels the container with it
 *     and recreates the container when an entry carries a different one
 */
public record DeskEntry(String projectId, DesiredState desired, DeskSpec spec, String specHash) {

  public DeskEntry {
    if (projectId == null || projectId.isBlank()) {
      throw new IllegalArgumentException("a desk entry names no project");
    }
    if (desired == null) {
      throw new IllegalArgumentException("a desk entry has no desired state");
    }
    if (spec == null) {
      throw new IllegalArgumentException("a desk entry has no spec");
    }
    if (specHash == null || specHash.isBlank()) {
      throw new IllegalArgumentException("a desk entry has no spec hash");
    }
  }
}
