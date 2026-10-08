package eu.wohlben.qits.projectsdeskrunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projectsdeskrunner.protocol.DeskRunnerBinary;
import eu.wohlben.qits.runner.toolkit.KnownRunnerNamespaces;
import eu.wohlben.qits.runner.toolkit.OwnResources;
import eu.wohlben.qits.runner.toolkit.RunnerDocker;
import eu.wohlben.qits.runner.toolkit.RunnerIdentity;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The node namespace the dossier promises, spelled out: every name and label the front-desk runner
 * uses, derived from its identity — and the identity is the one the toolkit lists for this kind, so
 * no other runner on the node can reach into it.
 */
class DeskRunnerIdentityTest {

  private static final RunnerIdentity IDENTITY = DeskRunnerIdentity.IDENTITY;
  private static final String ID = "1a2b3c4d-0000-4000-8000-000000000001";
  private static final String PROJECT = "8273c743-e0bb-4d93-90d4-391aa10d9151";

  @Test
  void theIdentityHasTheRootsTheToolkitKnowsForThisKind() {
    assertTrue(KnownRunnerNamespaces.PROJECTS_DESK.matches(IDENTITY));
    assertEquals("projects-desk", IDENTITY.kind());
    assertEquals(DeskRunnerBinary.VERSION, IDENTITY.version());
    assertEquals("qits/qits-projects-desk-runner", IDENTITY.imageRepository());
    assertEquals("/var/lib/qits-projects-desk-runner", IDENTITY.defaultStateDir());
    assertEquals("/projects/api/runners/" + ID + "/register", IDENTITY.registerPath(ID));
    assertEquals("qits-projects-desk-runner", IDENTITY.serviceName());
    assertEquals("heldDesks", IDENTITY.vocabulary().heldField());
    assertEquals("adoptedDesks", IDENTITY.vocabulary().adoptedField());
  }

  @Test
  void everyNameOnTheNodeIsTheDossiersSpelling() {
    assertEquals(
        "qits-projects-desk-runner-1a2b3c4d-2026.1008.1",
        IDENTITY.containerName(ID, "2026.1008.1"));
    assertEquals("qits-projects-desk-runner-state-1a2b3c4d", IDENTITY.stateVolume(ID));
    assertEquals("qits.projects.desk.runner.process", IDENTITY.processLabel());
    assertEquals("qits.projects.desk.runner.version", IDENTITY.versionLabel());
    assertEquals("qits.projects.desk.runner.", IDENTITY.driverNamespace());
    assertEquals("QITS_PROJECTS_DESK_RUNNER_URL", IDENTITY.env("URL"));

    // The desk container and its volume: nodeName(runnerId, "desk-<projectId>") — the dossier's
    // derivation, never a literal. A full UUID project id still fits the toolkit's purpose rule.
    assertEquals(
        "qits-projects-desk-runner-desk-" + PROJECT + "-1a2b3c4d",
        IDENTITY.nodeName(ID, "desk-" + PROJECT));
    assertEquals(
        "qits-projects-desk-runner-dot-claude-1a2b3c4d", IDENTITY.nodeVolume(ID, "dot-claude"));
    assertEquals("qits-projects-desk-runner-m2-1a2b3c4d", IDENTITY.nodeVolume(ID, "m2"));
    assertEquals("qits-projects-desk-runner-pnpm-1a2b3c4d", IDENTITY.nodeVolume(ID, "pnpm"));

    OwnResources own =
        new OwnResources(IDENTITY, ID, new RunnerDocker(IDENTITY, "docker", Duration.ofSeconds(1)));
    assertEquals(
        Map.of("qits.projects.desk.runner", ID, "qits.projects.desk.runner.project", PROJECT),
        own.labels(PROJECT));
  }
}
