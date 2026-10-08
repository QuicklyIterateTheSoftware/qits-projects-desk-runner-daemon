package eu.wohlben.qits.projectsdeskrunner.protocol;

/**
 * qits-projects → runner: pull {@code image}, run it with no environment, see it running, remove
 * it, and answer {@link HealthChecked}. A new runner is quarantined until one passes.
 *
 * @param requestId the asker's id, echoed in the answer
 * @param image the project-agent image to run
 */
public record HealthCheck(String requestId, String image) implements DeskRunnerMessage {}
