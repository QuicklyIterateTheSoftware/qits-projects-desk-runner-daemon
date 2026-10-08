package eu.wohlben.qits.projectsdeskrunner.protocol;

/**
 * Runner → qits-projects: the answer to {@link HealthCheck}. {@code ok} lifts a new runner's
 * quarantine; {@code detail} says what was done, or what failed.
 *
 * @param requestId the {@link HealthCheck#requestId()} this answers
 * @param ok the image ran
 * @param detail a line for a person
 */
public record HealthChecked(String requestId, boolean ok, String detail)
    implements DeskRunnerMessage {}
