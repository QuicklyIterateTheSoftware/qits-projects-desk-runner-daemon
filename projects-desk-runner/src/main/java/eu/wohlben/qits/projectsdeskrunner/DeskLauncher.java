package eu.wohlben.qits.projectsdeskrunner;

import eu.wohlben.qits.containers.driver.ContainersDriver;
import eu.wohlben.qits.containers.driver.spec.ContainerSpec;
import eu.wohlben.qits.containers.driver.spec.LifecyclePolicy;
import eu.wohlben.qits.projectsdeskrunner.protocol.DesiredState;
import eu.wohlben.qits.projectsdeskrunner.protocol.DeskEntry;
import eu.wohlben.qits.projectsdeskrunner.protocol.DeskSpec;
import eu.wohlben.qits.runner.toolkit.RunnerDocker;
import eu.wohlben.qits.runner.toolkit.RunnerIdentity;
import eu.wohlben.qits.runner.toolkit.RunnerSession;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * One desk entry of an {@code estate} or a {@code take}, converged on the node through
 * qits-containers-driver under the runner's label namespace — qits-workspaces-runner-daemon's
 * {@code WorkspaceLauncher} ladder, with the server's desired state:
 *
 * <ul>
 *   <li><b>RUNNING</b>, no container → the image pulled when absent (under the runner's own login),
 *       every external node volume asked after ({@code launchFailed} when one is missing), the desk
 *       volume and the runner's own node volumes made labelled, then {@code run};
 *   <li><b>RUNNING</b>, a container whose {@code qits.projects.desk.runner.spec-hash} label is the
 *       entry's {@code specHash} → {@code start} when it is not running, else nothing;
 *   <li><b>RUNNING</b>, a container of another hash → {@code stop}, {@code rm} the container —
 *       <b>never its volume</b>, which holds the work — then {@code run} the new spec;
 *   <li><b>RUNNING</b>, a container whose {@code qits.projects.desk.runner.mounts} label is not
 *       {@link NodeVolumes#mountsHash} (or that has none) → the same recreation: it was run with
 *       other node volumes, e.g. its runner's own before the install line named the shared ones;
 *   <li><b>STOPPED</b> → {@code stop} when it runs; the container and its volume are kept.
 * </ul>
 *
 * <p>The hash is the server's ({@code front_desk.spec_hash}), carried on the frame and written to
 * the label as is: the runner never computes one, so the two can never disagree about a canonical
 * form.
 *
 * <p>What a created desk is, beyond the spec: the derived name; the default bridge (the spec has no
 * network field), no extra host, no docker socket; {@code --init}; {@link
 * ContainerSpec.PullPolicy#MISSING}; {@link LifecyclePolicy#explicitLifetime()}, so {@code
 * --restart unless-stopped} and a desk survives a node reboot; the desk volume on {@code
 * /workspace}, {@code dot-claude} on {@code /claude-home}, {@code m2} on {@code /caches/m2} and
 * {@code pnpm} on {@code /caches/pnpm}; the spec's limits with {@code capDropAll} and {@code
 * noNewPrivileges} off, as the desk container has always run.
 *
 * <p>The spec's environment carries the desk's own token and is never logged, here or anywhere the
 * runner writes.
 */
final class DeskLauncher {

  private static final Logger LOG = Logger.getLogger(DeskLauncher.class);

  /** The suffix of the label a desk container's spec hash rides under. */
  static final String SPEC_HASH_LABEL_SUFFIX = "spec-hash";

  /** The suffix of the label a desk container's node mounts ({@link NodeVolumes#mountsHash}) ride. */
  static final String MOUNTS_LABEL_SUFFIX = "mounts";

  /** What a convergence left: whether the desk now runs, and why not when it failed. */
  record Outcome(boolean running, String failure) {

    static Outcome nowRunning() {
      return new Outcome(true, null);
    }

    static Outcome notRunning() {
      return new Outcome(false, null);
    }

    static Outcome failed(String detail) {
      return new Outcome(false, detail);
    }

    boolean failed() {
      return failure != null;
    }
  }

  private final RunnerDocker docker;
  private final DeskNames names;
  private final NodeVolumes volumes;
  private final DeskContainers containers;

  DeskLauncher(
      RunnerDocker docker, DeskNames names, NodeVolumes volumes, DeskContainers containers) {
    this.docker = docker;
    this.names = names;
    this.volumes = volumes;
    this.containers = containers;
  }

  /** {@code qits.projects.desk.runner.spec-hash}: own-namespace, so only the runner writes it. */
  static String specHashLabel(RunnerIdentity identity) {
    return identity.driverNamespace() + SPEC_HASH_LABEL_SUFFIX;
  }

  /** {@code qits.projects.desk.runner.mounts}: the node volumes the desk was run with, hashed. */
  static String mountsLabel(RunnerIdentity identity) {
    return identity.driverNamespace() + MOUNTS_LABEL_SUFFIX;
  }

  /** Bring the node to the entry's desired state. Never throws: a failure is the outcome's. */
  Outcome converge(DeskEntry entry, RunnerSession session) {
    String projectId = entry.projectId();
    String name;
    Optional<DeskContainers.Seen> seen;
    try {
      name = names.container(projectId);
      seen = containers.inspect(name);
    } catch (RuntimeException e) {
      return failed(projectId, e.getMessage());
    }
    ContainersDriver driver = session.driver();
    Duration deadline = docker.deadline();
    if (entry.desired() == DesiredState.STOPPED) {
      if (seen.isPresent() && seen.get().running()) {
        ContainersDriver.OpResult stopped = driver.stop(name, deadline);
        if (!stopped.ok() && !DeskContainers.absent(stopped.detail())) {
          return failed(projectId, "docker could not stop " + name + ": " + brief(stopped.detail()));
        }
        LOG.infof("projects-desk-runner stopped the desk of project %s", projectId);
      }
      return Outcome.notRunning();
    }
    String mounts = volumes.mountsHash();
    if (seen.isPresent()
        && entry.specHash().equals(seen.get().specHash())
        && mounts.equals(seen.get().mounts())) {
      if (seen.get().running()) {
        return Outcome.nowRunning();
      }
      ContainersDriver.OpResult started = driver.start(name, deadline);
      if (!started.ok()) {
        return failed(projectId, "docker could not start " + name + ": " + brief(started.detail()));
      }
      LOG.infof("projects-desk-runner started the desk of project %s again", projectId);
      return Outcome.nowRunning();
    }
    // An external node volume is the operator's: asked after, never made — a -v naming one that is
    // not there would create it empty, unlabelled, and the desk would run with no login. Asked
    // before an old container goes, so a missing one never costs a desk that still runs.
    Optional<String> missing;
    try {
      missing = volumes.missing(docker);
    } catch (RuntimeException e) {
      return failed(projectId, e.getMessage());
    }
    if (missing.isPresent()) {
      return failed(projectId, missing.get());
    }
    if (seen.isPresent()) {
      // Another spec: the container goes, its volume stays — the work lives there, not here. A
      // graceful stop first, because rm -f is a SIGKILL (qits-1064's reason in the workspaces runner).
      if (seen.get().running()) {
        ContainersDriver.OpResult stopped = driver.stop(name, deadline);
        if (!stopped.ok() && !DeskContainers.absent(stopped.detail())) {
          LOG.warnf(
              "projects-desk-runner could not stop %s before recreating it: %s",
              name, stopped.detail());
        }
      }
      ContainersDriver.OpResult gone = driver.remove(name, deadline);
      if (!gone.ok() && !DeskContainers.absent(gone.detail())) {
        return failed(
            projectId, "docker could not remove " + name + "'s old container: " + brief(gone.detail()));
      }
      LOG.infof(
          "projects-desk-runner recreates the desk of project %s: its %s changed",
          projectId,
          entry.specHash().equals(seen.get().specHash()) ? "node volumes" : "spec");
    }
    return create(projectId, name, entry.spec(), entry.specHash(), mounts, session);
  }

  private Outcome create(
      String projectId,
      String name,
      DeskSpec spec,
      String hash,
      String mounts,
      RunnerSession session) {
    ContainersDriver driver = session.driver();
    Duration deadline = docker.deadline();
    ContainerSpec container;
    try {
      container = containerSpec(projectId, spec);
    } catch (IllegalArgumentException refused) {
      return failed(projectId, "the desk spec was refused: " + refused.getMessage());
    }
    Optional<String> notHere = NodeImages.ensure(docker, session, spec.image());
    if (notHere.isPresent()) {
      return failed(projectId, "could not pull " + spec.image() + ": " + brief(notHere.get()));
    }
    ContainersDriver.OpResult desk = volumes.ensureDesk(driver, session.own(), projectId, deadline);
    if (!desk.ok()) {
      return failed(
          projectId,
          "docker could not create the volume " + volumes.desk(projectId) + ": " + brief(desk.detail()));
    }
    for (String node : volumes.owned()) {
      ContainersDriver.OpResult made = volumes.ensureNode(driver, node, deadline);
      if (!made.ok()) {
        return failed(
            projectId, "docker could not create the volume " + node + ": " + brief(made.detail()));
      }
    }
    Map<String, String> labels = new LinkedHashMap<>(session.own().labels(projectId));
    labels.put(specHashLabel(session.identity()), hash);
    labels.put(mountsLabel(session.identity()), mounts);
    ContainersDriver.Started started =
        driver.run(container, name, labels, LifecyclePolicy.explicitLifetime(), deadline);
    if (!started.started()) {
      return failed(projectId, "docker could not run " + name + ": " + brief(started.detail()));
    }
    LOG.infof("projects-desk-runner runs the desk of project %s as %s", projectId, name);
    return Outcome.nowRunning();
  }

  /** The driver's spec of a project's desk container: the server's, with the runner's choices. */
  ContainerSpec containerSpec(String projectId, DeskSpec spec) {
    String name = names.container(projectId);
    ContainerSpec.Builder b =
        ContainerSpec.builder(spec.image())
            .name(name)
            .user(spec.user())
            .init(true)
            .pullPolicy(ContainerSpec.PullPolicy.MISSING)
            .security(
                new ContainerSpec.SecurityPosture(
                    false,
                    false,
                    spec.memory(),
                    spec.memorySwap(),
                    spec.pidsLimit(),
                    spec.cpus(),
                    spec.oomScoreAdj()));
    spec.env().forEach(b::env);
    b.mount(volumes.desk(projectId), NodeVolumes.WORKSPACE_TARGET);
    // The node's, not this desk's: the driver keeps the two apart for what a removal reads.
    b.shared(volumes.dotClaude(), NodeVolumes.CLAUDE_HOME_TARGET);
    b.shared(volumes.m2(), NodeVolumes.M2_TARGET);
    b.shared(volumes.pnpm(), NodeVolumes.PNPM_TARGET);
    return b.build();
  }

  private static Outcome failed(String projectId, String detail) {
    LOG.warnf("projects-desk-runner could not converge the desk of project %s: %s", projectId, detail);
    return Outcome.failed(detail);
  }

  /** The tail of docker's words: the last 400 characters, where its error is. */
  static String brief(String text) {
    String s = text == null ? "" : text.strip();
    return s.length() > 400 ? "…" + s.substring(s.length() - 400) : s;
  }
}
