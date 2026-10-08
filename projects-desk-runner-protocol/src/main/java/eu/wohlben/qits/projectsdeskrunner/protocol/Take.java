package eu.wohlben.qits.projectsdeskrunner.protocol;

/**
 * qits-projects → runner: the answer to a {@code Reserve} when a desk was assigned to this runner.
 * Converged exactly as an {@link Estate} entry is.
 *
 * @param projectId the project the desk belongs to
 * @param desired whether its container should run
 * @param spec what the container is
 * @param specHash the server's hash of the canonical spec
 */
public record Take(String projectId, DesiredState desired, DeskSpec spec, String specHash)
    implements DeskRunnerMessage {

  /** The desk as an estate entry, for the one convergence both frames share. */
  public DeskEntry entry() {
    return new DeskEntry(projectId, desired, spec, specHash);
  }
}
