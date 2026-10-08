package eu.wohlben.qits.projectsdeskrunner.protocol;

import java.util.List;

/**
 * qits-projects → runner: the <b>full</b> list of desks this runner owns, and what each should be.
 * Sent on greeting (after the {@code Ack}), on every change and every estate interval.
 *
 * <p><b>{@code desks} is the server's list, and it wins.</b> A desk container in the runner's own
 * label namespace whose project is not on it is removed, and its desk volume at the next
 * housekeeping pass. So it is sent only from a successful read, never as an empty list on error —
 * and the codec refuses a frame that carries no list at all rather than reading the absence as
 * "owns nothing".
 *
 * @param desks every desk this runner owns
 * @param deskImage the pinned project-agent image, which housekeeping keeps
 */
public record Estate(List<DeskEntry> desks, String deskImage) implements DeskRunnerMessage {

  public Estate {
    desks = List.copyOf(desks);
  }
}
