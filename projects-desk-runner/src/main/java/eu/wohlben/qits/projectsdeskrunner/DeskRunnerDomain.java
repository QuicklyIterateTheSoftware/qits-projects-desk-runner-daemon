package eu.wohlben.qits.projectsdeskrunner;

import eu.wohlben.qits.containers.driver.ContainersDriver;
import eu.wohlben.qits.projectsdeskrunner.protocol.DeskEntry;
import eu.wohlben.qits.projectsdeskrunner.protocol.DeskRunnerCodec;
import eu.wohlben.qits.projectsdeskrunner.protocol.DeskRunnerMessage;
import eu.wohlben.qits.projectsdeskrunner.protocol.DeskState;
import eu.wohlben.qits.projectsdeskrunner.protocol.Estate;
import eu.wohlben.qits.projectsdeskrunner.protocol.HealthCheck;
import eu.wohlben.qits.projectsdeskrunner.protocol.HeldDesk;
import eu.wohlben.qits.projectsdeskrunner.protocol.Inventory;
import eu.wohlben.qits.projectsdeskrunner.protocol.LaunchFailed;
import eu.wohlben.qits.projectsdeskrunner.protocol.LoginPresence;
import eu.wohlben.qits.projectsdeskrunner.protocol.LoginState;
import eu.wohlben.qits.projectsdeskrunner.protocol.ProbeLogin;
import eu.wohlben.qits.projectsdeskrunner.protocol.Remove;
import eu.wohlben.qits.projectsdeskrunner.protocol.Removed;
import eu.wohlben.qits.projectsdeskrunner.protocol.Take;
import eu.wohlben.qits.runner.protocol.Ack;
import eu.wohlben.qits.runner.protocol.DomainCodec;
import eu.wohlben.qits.runner.protocol.RunnerCapabilities;
import eu.wohlben.qits.runner.toolkit.HostFacts;
import eu.wohlben.qits.runner.toolkit.HousekeepingContext;
import eu.wohlben.qits.runner.toolkit.HousekeepingPass;
import eu.wohlben.qits.runner.toolkit.OwnResources;
import eu.wohlben.qits.runner.toolkit.RunnerDocker;
import eu.wohlben.qits.runner.toolkit.RunnerDomain;
import eu.wohlben.qits.runner.toolkit.RunnerIdentity;
import eu.wohlben.qits.runner.toolkit.RunnerSession;
import eu.wohlben.qits.runner.toolkit.RunnerSettings;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import org.jboss.logging.Logger;

/**
 * <b>What the front-desk runner is for</b>, as qits-runner-toolkit's {@link RunnerDomain}: the
 * toolkit owns registration, the bearer, the socket, the slot ledger, self-update, decommission,
 * the heartbeat and housekeeping; this class owns the front-desk frames and the desk containers. Its
 * shape is qits-workspaces-runner-daemon's {@code WorkspacesRunnerDomain}, with the server's
 * desired state in place of take/stop/delete.
 *
 * <p><b>The {@code estate} is the desired state</b>: the full list of this runner's desks, each
 * converged by {@link DeskLauncher} — run, start, recreate on a spec-hash change, stop. After it
 * (and never before an {@code estate} on the current connection) a labelled desk container whose
 * project is not listed is stopped and removed ({@link OwnResources#reconcile}), its volume at the
 * next housekeeping pass ({@link DeskVolumesPass}). The node volumes go only with the runner, at
 * decommission. Nothing without the runner's label is ever touched.
 *
 * <p><b>A slot is a running desk</b>, counted by the toolkit's slot ledger and fed from docker: the
 * session start reports each running desk {@code taken}, a convergence that leaves a desk running
 * takes it and one that leaves it stopped releases it. The ledger sends {@code reserve} only while
 * fewer desks run than {@code slots}, and parks on {@code nothing}; nothing here decides when to
 * ask for work. A {@code take} always reports {@code taken} first — only that tells the ledger its
 * {@code reserve} was answered.
 *
 * <p><b>Rollover never drains.</b> {@link #quiescent()} is "no convergence in flight", never "holds
 * nothing": a desk container is node state that outlives the runner process, and its successor
 * adopts it by label at its session start (qits-624's reason; CI's drain is wrong here).
 *
 * <p><b>The {@code inventory}</b> goes out after every convergence and every {@link
 * Timing#inventoryEvery}; a listing docker did not answer is never sent — read as empty, it would
 * tell the server every desk is gone.
 */
public final class DeskRunnerDomain implements RunnerDomain<DeskRunnerMessage> {

  private static final Logger LOG = Logger.getLogger(DeskRunnerDomain.class);

  private static final DeskRunnerCodec CODEC = new DeskRunnerCodec();

  /**
   * The node's clocks: the health check's window and poll, the inventory's cadence, and the login
   * probe's — every five minutes while the login is ABSENT (or could not be told), hourly
   * otherwise.
   */
  record Timing(
      Duration healthWindow,
      Duration healthPoll,
      Duration inventoryEvery,
      Duration loginAbsentEvery,
      Duration loginPresentEvery) {

    static Timing defaults() {
      return new Timing(
          NodeHealthCheck.DEFAULT_WINDOW,
          NodeHealthCheck.DEFAULT_POLL,
          Duration.ofSeconds(60),
          Duration.ofMinutes(5),
          Duration.ofHours(1));
    }
  }

  private final RunnerIdentity identity = DeskRunnerIdentity.IDENTITY;
  private final RunnerDocker docker;
  private final String runnerId;
  private final Path stateDir;
  private final DeskNames names;
  private final NodeVolumes volumes;
  private final DeskContainers containers;
  private final DeskLauncher launcher;
  private final NodeHealthCheck healthCheck;
  private final LoginProbe loginProbe;
  private final Timing timing;
  private final Clock clock;

  /** The inventory tick and the login schedule; daemon threads, so they never hold the process. */
  private final ScheduledExecutorService timer;

  /** Estates, takes and removes under way or waiting: the rollover waits for zero, and only that. */
  private final AtomicInteger inFlight = new AtomicInteger();

  /** One convergence at a time: two frames never work on the node at once. */
  private final ReentrantLock converging = new ReentrantLock(true);

  /** The last convergence's failure per project, for {@code inventory}'s FAILED; cleared by success. */
  private final Map<String, String> failures = new ConcurrentHashMap<>();

  /**
   * Projects taken that no {@code estate} has listed yet: an {@code estate} the server sent before
   * it claimed the desk can be read after the take, and must not remove what the take just made.
   */
  private final Set<String> unconfirmed = ConcurrentHashMap.newKeySet();

  /** The latest {@code estate}, kept across reconnects for its image; null before the first. */
  private volatile Estate estate;

  /** This connection's {@code estate} projects; null until one arrived on this connection. */
  private volatile Set<String> sessionDesks;

  /** The user the desks run as, from the latest spec seen; what the login probe runs as. */
  private volatile String deskUser;

  /** The desk image this process last pruned old tags for; null before the first. */
  private volatile String lastPrunedImage;

  /** Whether this connection's {@code loginState} went out yet: the greeting's probe. */
  private volatile boolean loginGreeted;

  /** Between this session's {@code Ack} and its loss: when inventories are sent on the tick. */
  private volatile boolean live;

  /** Set once this runner learns it was deleted: from then on it starts nothing. */
  private volatile boolean decommissioning;

  /** The handle the toolkit gave: the same across reconnects, null before the first session. */
  private volatile RunnerSession session;

  private final Object inventoryLock = new Object();

  private ScheduledFuture<?> tick;
  private ScheduledFuture<?> nextProbe;

  /** Built by {@code RunnerLauncher} from the parsed settings, as every embedder's domain is. */
  public DeskRunnerDomain(RunnerSettings settings) {
    this(settings, ExternalVolumes.NONE);
  }

  /** The same, with the external node volumes {@link Main} parsed beside the settings. */
  DeskRunnerDomain(RunnerSettings settings, ExternalVolumes external) {
    this(settings, external, Timing.defaults(), Clock.systemUTC());
  }

  /** The same domain with the node's clocks a suite shrinks. */
  DeskRunnerDomain(RunnerSettings settings, Timing timing, Clock clock) {
    this(settings, ExternalVolumes.NONE, timing, clock);
  }

  /** The same domain with external node volumes and the node's clocks a suite shrinks. */
  DeskRunnerDomain(
      RunnerSettings settings, ExternalVolumes external, Timing timing, Clock clock) {
    this.docker = RunnerDocker.of(identity, settings);
    this.runnerId = settings.runnerId();
    this.stateDir = settings.stateDir();
    this.names = new DeskNames(identity, settings.runnerId());
    this.volumes = new NodeVolumes(identity, settings.runnerId(), external);
    this.containers = new DeskContainers(identity, settings.runnerId(), docker);
    this.launcher = new DeskLauncher(docker, names, volumes, containers);
    this.healthCheck =
        new NodeHealthCheck(docker, names, timing.healthWindow(), timing.healthPoll());
    this.loginProbe =
        new LoginProbe(
            identity,
            settings.runnerId(),
            new RunnerDocker(identity, settings.dockerBinary(), LoginProbe.DEADLINE),
            volumes);
    this.timing = timing;
    this.clock = clock;
    ScheduledThreadPoolExecutor pool =
        new ScheduledThreadPoolExecutor(
            2,
            r -> {
              Thread t = new Thread(r, "projects-desk-runner-node");
              t.setDaemon(true);
              return t;
            });
    pool.setRemoveOnCancelPolicy(true);
    this.timer = pool;
  }

  @Override
  public DomainCodec<DeskRunnerMessage> codec() {
    return CODEC;
  }

  @Override
  public RunnerCapabilities capabilities(HostFacts host) {
    return new RunnerCapabilities(host.docker(), host.arch(), host.os(), Map.of());
  }

  /**
   * The running desk containers, read from docker, each reported to the slot ledger as held: a
   * successor that adopts two running desks has two slots taken before it asks for more. Nothing is
   * removed here — the keep set is the server's {@code estate}, which arrives after the {@code Ack}.
   */
  @Override
  public void onSessionStart(RunnerSession session, List<String> carried) {
    this.session = session;
    live = false;
    sessionDesks = null;
    loginGreeted = false;
    holdRunning(session, null);
  }

  /**
   * Admitted: the runner's own agent home and caches are made labelled (before the operator's login
   * command could make the agent home unlabelled) — an external one is the operator's and is never
   * made — the {@code inventory} goes out, and the tick runs from here on.
   */
  @Override
  public void onAck(Ack ack, RunnerSession session) {
    this.session = session;
    if (decommissioning) {
      return;
    }
    live = true;
    for (String node : volumes.owned()) {
      ContainersDriver.OpResult made = volumes.ensureNode(session.driver(), node, docker.deadline());
      if (!made.ok()) {
        LOG.warnf("projects-desk-runner could not create %s: %s", node, made.detail());
      }
    }
    sendInventory(session);
    startTick();
  }

  /**
   * The host did not adopt these carried desks, so the ledger let their slots go — but a desk that
   * still runs still holds one. Whether it is still this runner's is the next {@code estate}'s to say.
   */
  @Override
  public void onDropped(List<String> workIds, RunnerSession session) {
    holdRunning(session, Set.copyOf(workIds));
  }

  @Override
  public void onSessionLost() {
    live = false;
    sessionDesks = null;
  }

  @Override
  public void onMessage(DeskRunnerMessage message, RunnerSession session) {
    this.session = session;
    switch (message) {
      case Estate e -> onEstate(e, session);
      case Take take -> onTake(take, session);
      case Remove remove -> onRemove(remove, session);
      case ProbeLogin _ -> probeLogin(session);
      case HealthCheck check -> onHealthCheck(check, session);
      default ->
          // inventory, launchFailed, removed, loginState and healthChecked are runner → server; a
          // host echoing one is not a conversation this has.
          LOG.debugf(
              "projects-desk-runner ignored a %s from its host", message.getClass().getSimpleName());
    }
  }

  @Override
  public boolean quiescent() {
    return inFlight.get() == 0;
  }

  /**
   * Stop every desk container, so nothing of this runner runs on past its deletion; the toolkit's
   * decommission helper then removes every container and volume labelled {@code
   * qits.projects.desk.runner=<id>} — the desks', the agent home's and the caches' — the state
   * volume and the runner's own container. No {@code teardown()} item is needed: everything the
   * runner creates carries that label. An external node volume ({@link ExternalVolumes}) carries
   * neither the label nor the runner's name prefix, so the helper can never reach it — it stays.
   */
  @Override
  public void onDecommission(RunnerSession session) {
    decommissioning = true;
    live = false;
    timer.shutdownNow();
    List<DeskContainers.Seen> seen;
    try {
      seen = containers.list();
    } catch (RuntimeException e) {
      LOG.warnf("projects-desk-runner could not list its desks to stop them: %s", e.getMessage());
      return;
    }
    for (DeskContainers.Seen s : seen) {
      if (!s.running()) {
        continue;
      }
      String name = names.container(s.projectId());
      ContainersDriver.OpResult stopped = session.driver().stop(name, docker.deadline());
      if (!stopped.ok()) {
        LOG.warnf("projects-desk-runner could not stop %s: %s", name, stopped.detail());
      }
    }
  }

  /**
   * The old desk images and the unlisted desk volumes, after the toolkit's own passes ({@code
   * RunnerImagesPass}, {@code DanglingImagesPass}).
   */
  @Override
  public List<HousekeepingPass> housekeeping() {
    return List.of(
        new DeskImagesPass(docker, this::deskImage),
        new DeskVolumesPass(volumes, this::volumesToKeep));
  }

  /** The latest {@code estate}'s desk image, or null before one. */
  String deskImage() {
    Estate e = estate;
    return e == null ? null : e.deskImage();
  }

  /** Stop the node's timers; the process ending does the same. For a suite's teardown. */
  void close() {
    timer.shutdownNow();
  }

  // --- the frames ---------------------------------------------------------------------------------

  /**
   * The server's full list. Desk containers of projects it does not list go first — stopped, then
   * removed — then every listed desk is converged, and the {@code inventory} says what is left.
   * Once the {@code deskImage} is on the node and names an image this process has not pruned for,
   * {@link DeskImagesPass} runs at once rather than at the next six-hourly housekeeping. The first
   * {@code estate} of a connection also probes the login: the greeting's {@code loginState}, sent
   * once the image to probe with is known.
   */
  private void onEstate(Estate e, RunnerSession session) {
    estate = e;
    if (decommissioning) {
      return;
    }
    inFlight.incrementAndGet();
    converging.lock();
    try {
      Set<String> listed = new LinkedHashSet<>();
      e.desks().forEach(entry -> listed.add(entry.projectId()));
      sessionDesks = Set.copyOf(listed);
      LOG.infof(
          "projects-desk-runner's estate: %d desk(s), desk image %s", listed.size(), e.deskImage());
      Set<String> keep = new LinkedHashSet<>(listed);
      keep.addAll(unconfirmed);
      if (stopOutside(keep, session)) {
        session.own().reconcile(keep);
      }
      unconfirmed.removeAll(listed);
      failures.keySet().retainAll(keep);
      for (DeskEntry entry : e.desks()) {
        converge(entry, session, false);
      }
    } finally {
      converging.unlock();
      inFlight.decrementAndGet();
    }
    look(session);
    pruneOldDeskImages();
    if (!loginGreeted) {
      loginGreeted = true;
      probeLogin(session);
    }
  }

  /**
   * The answer to the one outstanding {@code reserve}: the desk converged as the frame says, then
   * {@code taken} (the ledger's answer) and {@code released} again when the desk does not run.
   */
  private void onTake(Take take, RunnerSession session) {
    String projectId = take.projectId();
    inFlight.incrementAndGet();
    converging.lock();
    try {
      if (decommissioning) {
        session.send(new LaunchFailed(projectId, "this runner is being deleted"));
        session.taken(projectId);
        session.released(projectId);
        return;
      }
      unconfirmed.add(projectId);
      converge(take.entry(), session, true);
    } finally {
      converging.unlock();
      inFlight.decrementAndGet();
    }
    sendInventory(session);
  }

  /**
   * The container and its desk volume removed, then {@code removed}: nothing of the desk is left on
   * the node. Either already gone counts as gone; a graceful stop goes first, because {@code rm -f}
   * is a SIGKILL. A docker that did not answer gets no {@code removed} either: the server retries
   * rather than hearing one that is not true.
   */
  private void onRemove(Remove remove, RunnerSession session) {
    String projectId = remove.projectId();
    inFlight.incrementAndGet();
    converging.lock();
    try {
      String name;
      String volume;
      try {
        name = names.container(projectId);
        volume = volumes.desk(projectId);
      } catch (IllegalArgumentException notAName) {
        // No desk of this runner can carry a name it cannot derive: nothing is there to remove.
        LOG.warnf("projects-desk-runner was asked to remove an unnameable desk: %s", projectId);
        session.send(new Removed(projectId));
        return;
      }
      ContainersDriver driver = session.driver();
      ContainersDriver.OpResult stopped = driver.stop(name, docker.deadline());
      if (!stopped.ok() && !DeskContainers.absent(stopped.detail())) {
        LOG.warnf(
            "projects-desk-runner could not stop %s before removing it: %s", name, stopped.detail());
      }
      ContainersDriver.OpResult gone = driver.remove(name, docker.deadline());
      if (!gone.ok() && !DeskContainers.absent(gone.detail())) {
        LOG.warnf("projects-desk-runner could not remove %s: %s", name, gone.detail());
        return;
      }
      ContainersDriver.OpResult volumeGone = driver.removeVolume(volume, docker.deadline());
      if (!volumeGone.ok() && !DeskContainers.absent(volumeGone.detail())) {
        LOG.warnf("projects-desk-runner could not remove %s: %s", volume, volumeGone.detail());
        return;
      }
      failures.remove(projectId);
      unconfirmed.remove(projectId);
      LOG.infof("projects-desk-runner removed the desk of project %s", projectId);
      session.send(new Removed(projectId));
      if (session.held().contains(projectId)) {
        session.released(projectId);
      }
    } finally {
      converging.unlock();
      inFlight.decrementAndGet();
    }
    sendInventory(session);
  }

  /**
   * One entry converged, under {@link #converging}: {@code launchFailed} with docker's last words
   * when it failed, and the slot held exactly while the desk runs. A {@code take} always reports
   * {@code taken} — the ledger's only sign that its {@code reserve} was answered; anything else
   * reports it only for a desk not held yet, so a desk already running does not answer a {@code
   * reserve} it was never the answer to.
   */
  private void converge(DeskEntry entry, RunnerSession session, boolean answersReserve) {
    String projectId = entry.projectId();
    if (entry.spec().user() != null && !entry.spec().user().isBlank()) {
      deskUser = entry.spec().user();
    }
    DeskLauncher.Outcome outcome = launcher.converge(entry, session);
    if (outcome.failed()) {
      failures.put(projectId, outcome.failure());
      session.send(new LaunchFailed(projectId, outcome.failure()));
    } else {
      failures.remove(projectId);
    }
    boolean held = session.held().contains(projectId);
    if (answersReserve || (outcome.running() && !held)) {
      session.taken(projectId);
      held = true;
    }
    if (!outcome.running() && held) {
      session.released(projectId);
    }
  }

  /**
   * Stop every running desk container {@link OwnResources#reconcile} is about to remove — those
   * whose project is not in {@code keep} — so the reconcile's {@code rm -f} finds them stopped
   * rather than SIGKILLing them.
   *
   * @return false when docker did not answer the listing — the reconcile then waits for the next
   *     {@code estate} rather than remove what nothing stopped
   */
  private boolean stopOutside(Set<String> keep, RunnerSession session) {
    List<DeskContainers.Seen> seen;
    try {
      seen = containers.list();
    } catch (RuntimeException e) {
      LOG.warnf(
          "projects-desk-runner could not list its desks; reconciling nothing this time: %s",
          e.getMessage());
      return false;
    }
    for (DeskContainers.Seen s : seen) {
      if (!s.running() || keep.contains(s.projectId())) {
        continue;
      }
      String name;
      try {
        name = names.container(s.projectId());
      } catch (IllegalArgumentException notADesk) {
        continue;
      }
      ContainersDriver.OpResult stopped = session.driver().stop(name, docker.deadline());
      if (!stopped.ok() && !DeskContainers.absent(stopped.detail())) {
        LOG.warnf("projects-desk-runner could not stop %s: %s", name, stopped.detail());
      }
    }
    return true;
  }

  private void onHealthCheck(HealthCheck check, RunnerSession session) {
    if (decommissioning) {
      return;
    }
    session.send(healthCheck.run(session, check.requestId(), check.image()));
  }

  /**
   * Probe now, send {@code loginState} when the probe could tell, and schedule the next: in {@link
   * Timing#loginAbsentEvery} while the login is ABSENT or could not be told, else in {@link
   * Timing#loginPresentEvery}.
   */
  private void probeLogin(RunnerSession session) {
    if (decommissioning) {
      return;
    }
    Optional<LoginPresence> presence = loginProbe.probe(session.driver(), deskImage(), deskUser);
    presence.ifPresent(p -> session.send(new LoginState(p)));
    Duration next =
        presence.orElse(LoginPresence.ABSENT) == LoginPresence.PRESENT
            ? timing.loginPresentEvery()
            : timing.loginAbsentEvery();
    synchronized (timer) {
      if (nextProbe != null) {
        nextProbe.cancel(false);
      }
      if (timer.isShutdown()) {
        return;
      }
      nextProbe =
          timer.schedule(
              () -> {
                RunnerSession s = this.session;
                if (s != null && live) {
                  probeLogin(s);
                }
              },
              next.toMillis(),
              TimeUnit.MILLISECONDS);
    }
  }

  /** {@link DeskImagesPass} at once, the first time a {@code deskImage} is on the node. */
  private void pruneOldDeskImages() {
    String image = deskImage();
    if (image == null || image.isBlank() || image.equals(lastPrunedImage)) {
      return;
    }
    if (!NodeImages.present(docker, image)) {
      return; // the old tags are the only thing on the node still proven to run
    }
    lastPrunedImage = image;
    new DeskImagesPass(docker, this::deskImage).run(housekeepingContext());
  }

  /** The same context {@link eu.wohlben.qits.runner.toolkit.Housekeeping} hands every pass. */
  private HousekeepingContext housekeepingContext() {
    return new HousekeepingContext(
        identity,
        runnerId,
        docker.driver(),
        new OwnResources(identity, runnerId, docker),
        docker.deadline(),
        stateDir,
        clock,
        LOG);
  }

  // --- the node, as docker reports it -------------------------------------------------------------

  /**
   * Report every running desk container as held — of {@code only}, when given. A listing that
   * failed reports nothing.
   */
  private void holdRunning(RunnerSession session, Set<String> only) {
    List<DeskContainers.Seen> seen;
    try {
      seen = containers.list();
    } catch (RuntimeException e) {
      LOG.warnf("projects-desk-runner could not list its desks: %s", e.getMessage());
      return;
    }
    List<String> held = session.held();
    for (DeskContainers.Seen s : seen) {
      String projectId = s.projectId();
      if (s.running() && !held.contains(projectId) && (only == null || only.contains(projectId))) {
        session.taken(projectId);
      }
    }
  }

  private void startTick() {
    synchronized (timer) {
      if (tick != null || timer.isShutdown()) {
        return;
      }
      long every = timing.inventoryEvery().toMillis();
      tick = timer.scheduleWithFixedDelay(this::tickOnce, every, every, TimeUnit.MILLISECONDS);
    }
  }

  /** The periodic look; skipped while a convergence holds the node, which reports for itself. */
  private void tickOnce() {
    RunnerSession s = session;
    if (s == null || !live || decommissioning) {
      return;
    }
    if (!converging.tryLock()) {
      return;
    }
    try {
      look(s);
    } catch (RuntimeException e) {
      LOG.debugf("projects-desk-runner's look failed: %s", e.getMessage());
    } finally {
      converging.unlock();
    }
  }

  /**
   * Release the slot of every held desk docker no longer reports running — a desk that stopped by
   * itself, or one the reconcile removed — then send the {@code inventory}.
   */
  private void look(RunnerSession s) {
    List<DeskContainers.Seen> now;
    try {
      now = containers.list();
    } catch (RuntimeException e) {
      LOG.warnf("projects-desk-runner sends no inventory: %s", e.getMessage());
      return;
    }
    Set<String> running = new LinkedHashSet<>();
    now.stream().filter(DeskContainers.Seen::running).forEach(seen -> running.add(seen.projectId()));
    for (String held : s.held()) {
      if (!running.contains(held)) {
        s.released(held);
      }
    }
    sendInventory(s, now);
  }

  /** The {@code inventory} from a fresh listing; nothing when docker did not answer it. */
  private void sendInventory(RunnerSession session) {
    List<DeskContainers.Seen> now;
    try {
      now = containers.list();
    } catch (RuntimeException e) {
      LOG.warnf("projects-desk-runner sends no inventory: %s", e.getMessage());
      return;
    }
    sendInventory(session, now);
  }

  /**
   * What of the desks is on this node: every desk container RUNNING or STOPPED with its spec-hash
   * label, every desk whose last convergence failed FAILED with why, and every desk the latest
   * {@code estate} (or an unlisted take) names that has no container ABSENT; sorted by project.
   */
  private void sendInventory(RunnerSession session, List<DeskContainers.Seen> now) {
    synchronized (inventoryLock) {
      Map<String, HeldDesk> desks = new HashMap<>();
      for (DeskContainers.Seen seen : now) {
        desks.put(
            seen.projectId(),
            new HeldDesk(
                seen.projectId(),
                seen.running() ? DeskState.RUNNING : DeskState.STOPPED,
                seen.specHash(),
                null));
      }
      failures.forEach(
          (projectId, detail) -> {
            HeldDesk seen = desks.get(projectId);
            desks.put(
                projectId,
                new HeldDesk(
                    projectId, DeskState.FAILED, seen == null ? null : seen.specHash(), detail));
          });
      Set<String> named = new LinkedHashSet<>(unconfirmed);
      Estate e = estate;
      if (e != null) {
        e.desks().forEach(entry -> named.add(entry.projectId()));
      }
      for (String projectId : named) {
        desks.putIfAbsent(projectId, new HeldDesk(projectId, DeskState.ABSENT, null, null));
      }
      List<HeldDesk> sorted = new ArrayList<>(desks.values());
      sorted.sort(Comparator.comparing(HeldDesk::projectId));
      session.send(new Inventory(sorted));
    }
  }

  /** The projects whose volumes stay: this connection's {@code estate} and the unlisted takes. */
  private Optional<Set<String>> volumesToKeep() {
    Set<String> listed = sessionDesks;
    if (listed == null) {
      return Optional.empty();
    }
    Set<String> keep = new LinkedHashSet<>(listed);
    keep.addAll(unconfirmed);
    return Optional.of(keep);
  }
}
