package eu.wohlben.qits.projectsdeskrunner.protocol;

/**
 * Whether the agent CLI is logged in on the runner's node — in its {@code dot-claude} volume — as
 * the login probe found it. Spelled on the wire as the constant's name. Presence only: the probe
 * never reads, copies or sends a credential.
 */
public enum LoginPresence {
  /** The probe ran and found a login. */
  PRESENT,
  /** The probe found none: the projects runners page shows the login command. */
  ABSENT
}
