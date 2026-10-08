package eu.wohlben.qits.projectsdeskrunner;

import eu.wohlben.qits.containers.driver.ContainersDriver;
import eu.wohlben.qits.runner.toolkit.HousekeepingContext;
import eu.wohlben.qits.runner.toolkit.HousekeepingPass;
import eu.wohlben.qits.runner.toolkit.OwnResources;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The {@code /workspace} volumes of desks this runner no longer has: every volume labelled {@code
 * qits.projects.desk.runner=<id>} that is a desk's ({@link NodeVolumes#projectOf}) and whose
 * project the latest {@code estate} of THIS connection does not list, removed at the housekeeping
 * pass after it. A copy of qits-workspaces-runner-daemon's {@code WorkspaceVolumesPass}.
 *
 * <p><b>Why here, and not with the containers at the {@code estate}.</b> A volume is the work —
 * possibly unpushed — and {@link OwnResources} never removes one; a container is cheap to make again
 * and goes at once. So the volume waits for the next pass, and docker's own refusal to remove a
 * volume a container still mounts is the last belt under it. (A {@code remove} frame is the other
 * way a desk volume goes: the server asked for exactly that.)
 *
 * <p>Nothing before an {@code estate} of this connection: the keep set is the server's list, and
 * with no list there is nothing to compare against — never "owns nothing". The node volumes ({@code
 * dot-claude}, {@code m2}, {@code pnpm}) are no desk's and are never candidates; neither is anything
 * without the runner's label.
 */
final class DeskVolumesPass implements HousekeepingPass {

  private final NodeVolumes volumes;
  private final Supplier<Optional<Set<String>>> keep;

  /**
   * @param keep the projects to keep — this connection's {@code estate} plus the takes it has not
   *     listed yet — or empty before this connection's first {@code estate}
   */
  DeskVolumesPass(NodeVolumes volumes, Supplier<Optional<Set<String>>> keep) {
    this.volumes = volumes;
    this.keep = keep;
  }

  @Override
  public String name() {
    return "desk-volumes";
  }

  @Override
  public void run(HousekeepingContext context) {
    Optional<Set<String>> kept = keep.get();
    if (kept.isEmpty()) {
      context.log().infof("projects-desk-runner keeps every desk volume: no estate yet");
      return;
    }
    int removed = 0;
    for (String name : context.own().volumes()) {
      Optional<String> project = volumes.projectOf(name);
      if (project.isEmpty() || kept.get().contains(project.get())) {
        continue;
      }
      ContainersDriver.OpResult gone = context.driver().removeVolume(name, context.deadline());
      if (gone.ok()) {
        removed++;
        context
            .log()
            .infof("projects-desk-runner removed %s: project %s is not its", name, project.get());
      } else {
        context.log().warnf("projects-desk-runner could not remove %s: %s", name, gone.detail());
      }
    }
    if (removed > 0) {
      context.log().infof("projects-desk-runner removed %d desk volume(s)", removed);
    }
  }
}
