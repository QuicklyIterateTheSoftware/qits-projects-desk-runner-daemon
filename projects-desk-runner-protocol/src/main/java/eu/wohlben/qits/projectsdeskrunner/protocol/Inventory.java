package eu.wohlben.qits.projectsdeskrunner.protocol;

import java.util.List;

/**
 * Runner → qits-projects: what of the desks is on this node, read from docker under the runner's
 * own label. Sent after every convergence and every 60 s. Like {@link Estate}, it is sent only from
 * a listing docker answered, and the codec refuses a frame that carries no list.
 *
 * @param desks every desk the runner knows of, with its state
 */
public record Inventory(List<HeldDesk> desks) implements DeskRunnerMessage {

  public Inventory {
    desks = List.copyOf(desks);
  }
}
