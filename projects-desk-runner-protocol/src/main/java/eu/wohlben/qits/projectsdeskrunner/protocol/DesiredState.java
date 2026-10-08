package eu.wohlben.qits.projectsdeskrunner.protocol;

/**
 * What qits-projects wants of one desk on this runner's node. Spelled on the wire as the
 * constant's name. It is an instruction, so a value this version does not know is refused by
 * {@link DeskRunnerCodec} rather than guessed at.
 */
public enum DesiredState {
  /** The desk container runs: created when absent, started when exited, recreated on a new spec. */
  RUNNING,
  /** The desk container is stopped when running; it and its desk volume are kept. */
  STOPPED
}
