package eu.wohlben.qits.projectsdeskrunner.protocol;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a desk container is, as qits-projects composes it for an {@link Estate} entry or a {@link
 * Take}.
 *
 * <p><b>The shape is the boundary.</b> This arrives over a socket and becomes an argv to a
 * root-equivalent daemon, so it can express only what a front desk needs: an image, an
 * environment, a user and limits. There is no network, no extra host, no docker socket, no mount
 * and no free-form argv — the runner derives the desk volume and the node volumes, and where they
 * land, from its own identity. A capability this record cannot express is a change to it, reviewed
 * as one.
 *
 * <p>The environment keeps its arrival order (never {@code Map.copyOf}, whose iteration order is
 * salted per JVM), so a hash over the canonical spec is the same in every process.
 *
 * @param image the public project-agent image reference
 * @param env the container's environment. It carries credentials; never logged
 * @param user {@code --user}, or null for the image's own
 * @param memory {@code --memory}, or null for none
 * @param memorySwap {@code --memory-swap}, or null for none
 * @param pidsLimit {@code --pids-limit}, or null for none
 * @param cpus {@code --cpus}, or null for none
 * @param oomScoreAdj {@code --oom-score-adj}, or null for none
 */
public record DeskSpec(
    String image,
    Map<String, String> env,
    String user,
    String memory,
    String memorySwap,
    Long pidsLimit,
    String cpus,
    Integer oomScoreAdj) {

  public DeskSpec {
    if (image == null || image.isBlank()) {
      throw new IllegalArgumentException("a desk spec names no image");
    }
    env =
        env == null || env.isEmpty()
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(env));
  }
}
