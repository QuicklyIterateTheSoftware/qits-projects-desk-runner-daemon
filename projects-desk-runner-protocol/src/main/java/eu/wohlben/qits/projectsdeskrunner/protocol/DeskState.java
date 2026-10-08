package eu.wohlben.qits.projectsdeskrunner.protocol;

/**
 * What one desk is on the node, as the runner read it from docker for an {@link Inventory}.
 * Spelled on the wire as the constant's name.
 */
public enum DeskState {
  /** The desk container exists and docker reports it running. */
  RUNNING,
  /** The desk container exists and is not running. */
  STOPPED,
  /** No desk container for this project on the node. */
  ABSENT,
  /** The last convergence of this desk failed; {@code detail} says why. */
  FAILED
}
