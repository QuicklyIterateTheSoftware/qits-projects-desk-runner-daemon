package eu.wohlben.qits.projectsdeskrunner;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * A desk's name read back to its project: only a name this runner would itself give a desk
 * answers, so {@link DeskVolumesPass} can never take a node volume, another runner's desk or a
 * look-alike for a desk of its own.
 */
class DeskNamesTest {

  private static final String RUNNER = "1a2b3c4d-0000-4000-8000-000000000001";
  private static final String PROJECT = "8273c743-e0bb-4d93-90d4-391aa10d9151";

  private final NodeVolumes volumes = new NodeVolumes(DeskRunnerIdentity.IDENTITY, RUNNER);
  private final DeskNames names = new DeskNames(DeskRunnerIdentity.IDENTITY, RUNNER);

  @Test
  void aDesksVolumeAndContainerShareTheDerivedNameAndReadBackToTheProject() {
    String name = "qits-projects-desk-runner-desk-" + PROJECT + "-1a2b3c4d";
    assertEquals(name, names.container(PROJECT));
    assertEquals(name, volumes.desk(PROJECT));
    assertEquals(Optional.of(PROJECT), volumes.projectOf(name));
  }

  @Test
  void nothingElseReadsBackAsADesk() {
    for (String other :
        new String[] {
          volumes.dotClaude(),
          volumes.m2(),
          volumes.pnpm(),
          "qits-projects-desk-runner-desk-" + PROJECT + "-9f8e7d6c",
          "qits-projects-desk-runner-desk-" + PROJECT + "-1a2b3c4d-copy",
          "qits-workspaces-runner-desk-" + PROJECT + "-1a2b3c4d",
          "qits-projects-desk-runner-state-1a2b3c4d",
          "qits-projects-desk-runner-desk--1a2b3c4d",
          names.healthCheck()
        }) {
      assertEquals(Optional.empty(), volumes.projectOf(other), other);
    }
  }
}
