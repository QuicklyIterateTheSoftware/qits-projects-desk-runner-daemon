package eu.wohlben.qits.projectsdeskrunner;

import eu.wohlben.qits.projectsdeskrunner.protocol.DeskRunnerCodec;
import eu.wohlben.qits.projectsdeskrunner.protocol.DeskRunnerMessage;
import eu.wohlben.qits.runner.protocol.Ack;
import eu.wohlben.qits.runner.protocol.DomainCodec;
import eu.wohlben.qits.runner.protocol.RunnerCapabilities;
import eu.wohlben.qits.runner.toolkit.HostFacts;
import eu.wohlben.qits.runner.toolkit.RunnerDomain;
import eu.wohlben.qits.runner.toolkit.RunnerSession;
import eu.wohlben.qits.runner.toolkit.RunnerSettings;
import java.util.List;
import java.util.Map;
import org.jboss.logging.Logger;

/**
 * <b>What the front-desk runner is for</b>, as qits-runner-toolkit's {@link RunnerDomain}: the
 * toolkit owns registration, the bearer, the socket, the slot ledger, self-update, decommission,
 * the heartbeat and housekeeping; this class owns the front-desk frames and the desk containers.
 *
 * <p><b>A skeleton, on purpose.</b> This first cut registers, greets and answers nothing, so the
 * binary, the image and the protocol jar can be released and the server built against them. The
 * convergence — {@code estate}/{@code take} to desk containers, {@code remove}, the login probe,
 * the health check, the inventory and the housekeeping passes — is the next change, written to the
 * dossier's "Domain" section. Until then a frame from the host is logged and dropped, and nothing
 * on the node is created or removed: in particular the session start reconciles nothing, because
 * nothing may be removed before an {@code estate} has arrived on the current connection.
 *
 * <p><b>Rollover never drains</b>, here as in the workspaces runner: {@link #quiescent()} is "no
 * convergence in flight", never "holds nothing", because a desk container outlives the runner
 * process and its successor adopts it by label. With no convergence yet, it is always true.
 */
public final class DeskRunnerDomain implements RunnerDomain<DeskRunnerMessage> {

  private static final Logger LOG = Logger.getLogger(DeskRunnerDomain.class);

  private static final DeskRunnerCodec CODEC = new DeskRunnerCodec();

  /** Built by {@code RunnerLauncher} from the parsed settings, as every embedder's domain is. */
  public DeskRunnerDomain(RunnerSettings settings) {}

  @Override
  public DomainCodec<DeskRunnerMessage> codec() {
    return CODEC;
  }

  @Override
  public RunnerCapabilities capabilities(HostFacts host) {
    return new RunnerCapabilities(host.docker(), host.arch(), host.os(), Map.of());
  }

  /** Nothing is reconciled before an {@code estate} — and the skeleton has no estate yet. */
  @Override
  public void onSessionStart(RunnerSession session, List<String> carried) {}

  @Override
  public void onAck(Ack ack, RunnerSession session) {}

  @Override
  public void onMessage(DeskRunnerMessage message, RunnerSession session) {
    LOG.debugf(
        "projects-desk-runner does not handle %s yet", message.getClass().getSimpleName());
  }

  @Override
  public boolean quiescent() {
    return true;
  }

  /** The skeleton started nothing, so there is nothing of its own to stop. */
  @Override
  public void onDecommission(RunnerSession session) {}
}
