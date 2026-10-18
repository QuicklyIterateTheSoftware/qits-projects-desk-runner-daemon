package eu.wohlben.qits.projectsdeskrunner;

import eu.wohlben.qits.runner.toolkit.contracts.RunnerToolkitPact;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * The REST calls qits-runner-toolkit makes on this daemon's behalf, pacted as this daemon's own
 * consumer pacts (qits-1163): the token mint at qits-idp, the log export to qits-observability and
 * the register door at qits-projects-service, each through the real toolkit classes. Writes {@code
 * pacts/qits-projects-desk-runner-daemon_qits-runner-javalib_<provider>.json} under {@code
 * -Dgolden.update=true}; a provider whose states are not recorded yet is skipped, naming them.
 */
class ToolkitPactTest {

  @TestFactory
  Stream<DynamicTest> theToolkitsCalls() {
    return RunnerToolkitPact.forConsumer("qits-projects-desk-runner-daemon")
        .register(RunnerToolkitPact.PROJECTS, "registerDeskRunner", DeskRunnerIdentity.IDENTITY)
        .token()
        .logExport()
        .tests();
  }
}
