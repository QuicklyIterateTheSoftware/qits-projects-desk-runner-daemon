package eu.wohlben.qits.projectsdeskrunner;

import eu.wohlben.qits.projectsdeskrunner.protocol.DeskRunnerBinary;
import eu.wohlben.qits.projectsdeskrunner.protocol.DeskRunnerProtocol;
import eu.wohlben.qits.runner.toolkit.RunnerIdentity;

/**
 * Who the front-desk runner is: the one value it hands qits-runner-toolkit besides its domain.
 * Every label, container, volume and variable name the runner uses is derived from it — {@code
 * qits.projects.desk.runner=<id>}, {@code qits-projects-desk-runner-<id8>-<version>}, {@code
 * QITS_PROJECTS_DESK_RUNNER_URL}, … — and none is spelled anywhere else.
 *
 * <p>Built only from the protocol jar's constants, in {@link RunnerIdentity}'s order, so
 * qits-projects-service, which builds the same value for the install script it renders, cannot
 * name a different runner than this one is. Its roots are {@code
 * KnownRunnerNamespaces.PROJECTS_DESK}'s, which {@code DeskRunnerIdentityTest} holds.
 */
public final class DeskRunnerIdentity {

  /** The front-desk runner's identity, at the version this tree was built as. */
  public static final RunnerIdentity IDENTITY =
      new RunnerIdentity(
          DeskRunnerProtocol.KIND,
          DeskRunnerProtocol.ENV_PREFIX,
          DeskRunnerProtocol.LABEL_ROOT,
          DeskRunnerProtocol.NAME_PREFIX,
          DeskRunnerProtocol.IMAGE_REPOSITORY,
          DeskRunnerProtocol.STATE_DIR,
          DeskRunnerProtocol.REGISTER_PATH,
          DeskRunnerProtocol.SERVICE_NAME,
          DeskRunnerBinary.VERSION,
          DeskRunnerProtocol.CAPABILITY_VERSION,
          DeskRunnerProtocol.VOCABULARY,
          DeskRunnerProtocol.WORK_LABEL_SUFFIX);

  private DeskRunnerIdentity() {}
}
