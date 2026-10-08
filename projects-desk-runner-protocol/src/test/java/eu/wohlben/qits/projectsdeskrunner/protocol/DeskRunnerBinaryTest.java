package eu.wohlben.qits.projectsdeskrunner.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The pin resolves — the guard on this module's resource-filtering block, which is the one thing
 * about {@link DeskRunnerBinary} that can silently break. It asserts the shape and not a value: the
 * version is whatever stamped this tree, a CalVer after a release and a {@code -SNAPSHOT} before
 * the first one.
 */
class DeskRunnerBinaryTest {

  @Test
  void theVersionIsFilteredInAndIsACalVerOrASnapshot() {
    String version = DeskRunnerBinary.VERSION;
    assertFalse(version.isBlank(), "the version is blank");
    assertFalse(
        version.startsWith("$"),
        () -> "an unfiltered ${project.version} reached the jar: " + version);
    assertTrue(
        version.matches("[0-9][0-9.]*[0-9](-SNAPSHOT)?"),
        () -> "not a CalVer — is resource filtering still on? got: " + version);
  }

  @Test
  void theNamesAreTheOnesThePlatformAddressesTheRunnerBy() {
    // Literals, because each is a cross-repository contract: release.yml publishes the image under
    // this repository, qits-projects serves the socket and the register door at these paths, and
    // every label and name on a node is derived from these roots.
    assertEquals("qits/qits-projects-desk-runner", DeskRunnerProtocol.IMAGE_REPOSITORY);
    assertEquals("/projects/runners/socket", DeskRunnerProtocol.SOCKET_PATH);
    assertEquals("/projects/api/runners/{id}/register", DeskRunnerProtocol.REGISTER_PATH);
    assertEquals("projects-desk", DeskRunnerProtocol.KIND);
    assertEquals("QITS_PROJECTS_DESK_RUNNER_", DeskRunnerProtocol.ENV_PREFIX);
    assertEquals("qits.projects.desk.runner", DeskRunnerProtocol.LABEL_ROOT);
    assertEquals("qits-projects-desk-runner", DeskRunnerProtocol.NAME_PREFIX);
    assertEquals("/var/lib/qits-projects-desk-runner", DeskRunnerProtocol.STATE_DIR);
    assertEquals("qits-projects-desk-runner", DeskRunnerProtocol.SERVICE_NAME);
    assertEquals("project", DeskRunnerProtocol.WORK_LABEL_SUFFIX);
    assertEquals(1, DeskRunnerProtocol.CAPABILITY_VERSION);
    assertEquals("heldDesks", DeskRunnerProtocol.VOCABULARY.heldField());
    assertEquals("adoptedDesks", DeskRunnerProtocol.VOCABULARY.adoptedField());
  }
}
