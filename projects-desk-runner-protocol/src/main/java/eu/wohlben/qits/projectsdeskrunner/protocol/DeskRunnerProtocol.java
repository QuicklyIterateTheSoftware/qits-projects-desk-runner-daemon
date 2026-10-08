package eu.wohlben.qits.projectsdeskrunner.protocol;

import eu.wohlben.qits.runner.protocol.RunnerVocabulary;
import java.util.Set;

/**
 * The single source of truth for the front-desk runner's control socket: its address, its
 * capability version, its own frames' tags and field names, and the roots every name the runner
 * uses is derived from.
 *
 * <p>The lifecycle frames — {@code hello}, {@code ack}, {@code heartbeat}, {@code backlog}, {@code
 * reserve}, {@code nothing}, {@code upgrade}, {@code retire}, {@code quarantined} and {@code
 * reinstated} — are qits-runner-protocol's and byte-identical to every other runner's; none is
 * declared here. This class holds the front-desk frames, which travel through {@link
 * DeskRunnerCodec}:
 *
 * <pre>
 *   Hello{heldDesks} → Ack{slots, adoptedDesks} → Estate{desks[{projectId, desired, spec, specHash}], deskImage}
 *                    ← Inventory{desks[{projectId, state, specHash, detail}]}
 *   Reserve → Take{projectId, desired, spec, specHash} | Nothing   → LaunchFailed{projectId, detail}
 *   Remove{projectId} → Removed{projectId}
 *   ProbeLogin → LoginState{state}
 *   HealthCheck{requestId, image} → HealthChecked{requestId, ok, detail}
 * </pre>
 *
 * <p><b>Identity is not on the wire.</b> The runner presents a bearer minted from the idp client it
 * registered; qits-projects knows which runner this is before the first frame is read.
 *
 * <p><b>A project id is a JSON string</b> — qits-projects' project UUID, as the lifecycle frames'
 * held and adopted lists ({@code heldDesks}, {@code adoptedDesks}) carry it too.
 */
public final class DeskRunnerProtocol {

  /**
   * The capability version the runner announces in its {@code Hello} and qits-projects echoes in
   * its {@code Ack}. Bumped only when either side must branch on a change: a new frame or a new
   * field is not a bump, because both codecs drop an unknown type and ignore an unknown field.
   */
  public static final int CAPABILITY_VERSION = 1;

  /** The control socket's path below the service's public url: {@code wss://projects.qits.<d>…}. */
  public static final String SOCKET_PATH = "/projects/runners/socket";

  /**
   * The front-desk runner's names for the two lists the base carries across a lost socket: the
   * desk containers it holds ({@code Hello}) and the ones the host kept ({@code Ack}).
   */
  public static final RunnerVocabulary VOCABULARY =
      new RunnerVocabulary("heldDesks", "adoptedDesks");

  // --- the identity roots ------------------------------------------------------------------------
  //
  // qits-runner-toolkit's RunnerIdentity is built from exactly these, by the runner and by
  // qits-projects-service for its install script. They are java.* constants rather than an identity
  // because RunnerIdentity lives in the toolkit, which this jar must not depend on; both embedders
  // pass them in the same order, with DeskRunnerBinary.VERSION, CAPABILITY_VERSION and VOCABULARY.
  // The toolkit's KnownRunnerNamespaces.PROJECTS_DESK spells the first four too, and the runner
  // module's DeskRunnerIdentityTest holds them equal.

  /** {@code RunnerIdentity.kind}. */
  public static final String KIND = "projects-desk";

  /** {@code RunnerIdentity.envPrefix}: every variable is {@code QITS_PROJECTS_DESK_RUNNER_<NAME>}. */
  public static final String ENV_PREFIX = "QITS_PROJECTS_DESK_RUNNER_";

  /** {@code RunnerIdentity.labelRoot}: {@code qits.projects.desk.runner=<runnerId>} and below it. */
  public static final String LABEL_ROOT = "qits.projects.desk.runner";

  /** {@code RunnerIdentity.namePrefix}: every container and volume the runner names. */
  public static final String NAME_PREFIX = "qits-projects-desk-runner";

  /** {@code RunnerIdentity.imageRepository}, below the registry host and without a tag. */
  public static final String IMAGE_REPOSITORY = "qits/qits-projects-desk-runner";

  /** {@code RunnerIdentity.defaultStateDir}: where the state volume is mounted. */
  public static final String STATE_DIR = "/var/lib/qits-projects-desk-runner";

  /** {@code RunnerIdentity.registerPath}: the register door, {@code {id}} the runner id. */
  public static final String REGISTER_PATH = "/projects/api/runners/{id}/register";

  /** {@code RunnerIdentity.serviceName}: the runner's own log lands in {@code _service/<this>}. */
  public static final String SERVICE_NAME = "qits-projects-desk-runner";

  /** {@code RunnerIdentity.workLabelSuffix}: {@code qits.projects.desk.runner.project=<projectId>}. */
  public static final String WORK_LABEL_SUFFIX = "project";

  private DeskRunnerProtocol() {}

  /** The {@code "type"} discriminator values of the front-desk frames. */
  public static final class Type {
    // qits-projects -> runner
    public static final String ESTATE = "estate";
    public static final String TAKE = "take";
    public static final String REMOVE = "remove";
    public static final String PROBE_LOGIN = "probeLogin";
    public static final String HEALTH_CHECK = "healthCheck";
    // runner -> qits-projects
    public static final String INVENTORY = "inventory";
    public static final String LAUNCH_FAILED = "launchFailed";
    public static final String REMOVED = "removed";
    public static final String LOGIN_STATE = "loginState";
    public static final String HEALTH_CHECKED = "healthChecked";

    /** Every front-desk type — what {@link DeskRunnerCodec#types()} answers. */
    public static final Set<String> ALL =
        Set.of(
            ESTATE,
            TAKE,
            REMOVE,
            PROBE_LOGIN,
            HEALTH_CHECK,
            INVENTORY,
            LAUNCH_FAILED,
            REMOVED,
            LOGIN_STATE,
            HEALTH_CHECKED);

    private Type() {}
  }

  /** The field names of the front-desk frames and of the objects nested in them. */
  public static final class Field {
    public static final String TYPE = "type";
    public static final String DESKS = "desks";
    public static final String DESK_IMAGE = "deskImage";
    public static final String PROJECT_ID = "projectId";
    public static final String DESIRED = "desired";
    public static final String SPEC = "spec";
    public static final String SPEC_HASH = "specHash";
    public static final String STATE = "state";
    public static final String DETAIL = "detail";
    public static final String REQUEST_ID = "requestId";
    public static final String IMAGE = "image";
    public static final String OK = "ok";
    // DeskSpec
    public static final String ENV = "env";
    public static final String USER = "user";
    public static final String MEMORY = "memory";
    public static final String MEMORY_SWAP = "memorySwap";
    public static final String PIDS_LIMIT = "pidsLimit";
    public static final String CPUS = "cpus";
    public static final String OOM_SCORE_ADJ = "oomScoreAdj";

    private Field() {}
  }
}
