package eu.wohlben.qits.projectsdeskrunner.protocol;

/**
 * Runner → qits-projects: whether the agent CLI is logged in on this node. Sent after each probe
 * and at greeting.
 *
 * @param state what the probe found
 */
public record LoginState(LoginPresence state) implements DeskRunnerMessage {

  public LoginState {
    if (state == null) {
      throw new IllegalArgumentException("a login state has no state");
    }
  }
}
