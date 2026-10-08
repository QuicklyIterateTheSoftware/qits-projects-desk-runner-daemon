package eu.wohlben.qits.projectsdeskrunner;

import eu.wohlben.qits.runner.toolkit.LifecycleArgv;
import eu.wohlben.qits.runner.toolkit.RunnerDocker;
import eu.wohlben.qits.runner.toolkit.RunnerSession;
import java.util.Optional;

/**
 * An image on this node, pulled under the runner's own registry login ({@link
 * RunnerSession#registry()}: a throwaway {@code --config} holding the runner's client, never the
 * host's docker config) when it is not here yet — the step before a desk run and a health check.
 * Never a pull of an image that is already here: a pinned project-agent image does not move under
 * its tag, and a registry blip must not fail a start the node could make from what it has. A copy
 * of qits-workspaces-runner-daemon's {@code NodeImages}.
 */
final class NodeImages {

  private NodeImages() {}

  /** Empty when {@code image} is here (already, or pulled now); else why not. */
  static Optional<String> ensure(RunnerDocker docker, RunnerSession session, String image) {
    if (present(docker, image)) {
      return Optional.empty();
    }
    return session.registry().pull(image);
  }

  /** Whether {@code image} is on this node; a reference the belt refuses is not. */
  static boolean present(RunnerDocker docker, String image) {
    try {
      return docker.run(LifecycleArgv.imageInspect(docker.binary(), image)).ok();
    } catch (IllegalArgumentException refused) {
      return false;
    }
  }
}
