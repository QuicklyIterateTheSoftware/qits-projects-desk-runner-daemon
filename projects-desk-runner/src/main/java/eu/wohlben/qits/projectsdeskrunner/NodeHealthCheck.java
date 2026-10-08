package eu.wohlben.qits.projectsdeskrunner;

import eu.wohlben.qits.containers.driver.ContainersDriver;
import eu.wohlben.qits.containers.driver.spec.ContainerSpec;
import eu.wohlben.qits.containers.driver.spec.LifecyclePolicy;
import eu.wohlben.qits.projectsdeskrunner.protocol.HealthChecked;
import eu.wohlben.qits.runner.toolkit.RunnerDocker;
import eu.wohlben.qits.runner.toolkit.RunnerSession;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * The answer to {@code healthCheck{requestId, image}}: can this node run a desk at all? A copy of
 * qits-workspaces-runner-daemon's {@code NodeHealthCheck}: the requested image is pulled when it is
 * absent (under the runner's own registry login), started detached under the runner's ownership
 * label with no environment, watched until docker reports it {@code running} — within {@link
 * #DEFAULT_WINDOW} — and removed again; the outcome is {@code healthChecked}, echoing the request
 * id. A new runner is quarantined until one passes.
 *
 * <p><b>No environment, no mount, the default bridge.</b> Without {@code QITS_PROJECTS_DAEMON_URL}
 * the project-agent's daemon starts and idles, which is exactly "the image runs here"; anything
 * more would be a desk, and a desk is the server's to ask for.
 *
 * <p>The container carries {@code qits.projects.desk.runner=<id>} and no work label: it is nobody's
 * desk, and a leftover (a runner killed mid-check) is removed by the next check before it starts
 * and, at the latest, with everything else of the runner at decommission.
 */
final class NodeHealthCheck {

  private static final Logger LOG = Logger.getLogger(NodeHealthCheck.class);

  /** How long the started image has to be reported {@code running}. */
  static final Duration DEFAULT_WINDOW = Duration.ofSeconds(30);

  /** How often docker is asked within the window. */
  static final Duration DEFAULT_POLL = Duration.ofSeconds(1);

  private final RunnerDocker docker;
  private final DeskNames names;
  private final Duration window;
  private final Duration poll;

  NodeHealthCheck(RunnerDocker docker, DeskNames names, Duration window, Duration poll) {
    this.docker = docker;
    this.names = names;
    this.window = window;
    this.poll = poll;
  }

  /** Run the check for {@code image} and answer it under {@code requestId}. */
  HealthChecked run(RunnerSession session, String requestId, String image) {
    if (image == null || image.isBlank()) {
      return failed(requestId, "the health check names no image");
    }
    Optional<String> notHere = NodeImages.ensure(docker, session, image);
    if (notHere.isPresent()) {
      return failed(requestId, "could not pull " + image + ": " + brief(notHere.get()));
    }
    ContainersDriver driver = session.driver();
    Duration deadline = docker.deadline();
    String name = names.healthCheck();
    // A leftover of a check that was cut short holds the name; nothing of it is worth keeping.
    driver.remove(name, deadline);
    try {
      ContainersDriver.Started started =
          driver.run(
              ContainerSpec.builder(image).pullPolicy(ContainerSpec.PullPolicy.NEVER).build(),
              name,
              Map.of(session.identity().ownerLabel(), session.runnerId()),
              LifecyclePolicy.ephemeral(null),
              deadline);
      if (!started.started()) {
        return failed(requestId, "docker could not start " + image + ": " + brief(started.detail()));
      }
      String status = awaitRunning(driver, name, deadline);
      if (!"running".equals(status)) {
        return failed(
            requestId,
            image
                + " did not run within "
                + window.toSeconds()
                + "s (last status: "
                + (status.isEmpty() ? "unknown" : status)
                + ")");
      }
      return ok(requestId, image + " ran on this node");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return failed(requestId, "the health check was interrupted");
    } catch (RuntimeException e) {
      return failed(requestId, "docker did not answer: " + brief(e.getMessage()));
    } finally {
      ContainersDriver.OpResult gone = driver.remove(name, deadline);
      if (!gone.ok()) {
        LOG.warnf(
            "projects-desk-runner could not remove its health check %s: %s", name, gone.detail());
      }
    }
  }

  /** The last status docker reported, {@code running} as soon as it is. */
  private String awaitRunning(ContainersDriver driver, String name, Duration deadline)
      throws InterruptedException {
    long until = System.nanoTime() + window.toNanos();
    while (true) {
      Optional<ContainersDriver.Observed> seen = driver.inspect(name, deadline);
      String status = seen.map(ContainersDriver.Observed::status).orElse("");
      if ("running".equals(status) || System.nanoTime() >= until) {
        return status;
      }
      Thread.sleep(poll.toMillis());
    }
  }

  private static HealthChecked ok(String requestId, String detail) {
    LOG.infof("projects-desk-runner health check passed: %s", detail);
    return new HealthChecked(requestId, true, detail);
  }

  private static HealthChecked failed(String requestId, String detail) {
    LOG.warnf("projects-desk-runner health check failed: %s", detail);
    return new HealthChecked(requestId, false, detail);
  }

  private static String brief(String text) {
    return DeskLauncher.brief(text);
  }
}
