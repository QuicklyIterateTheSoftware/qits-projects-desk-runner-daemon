package eu.wohlben.qits.projectsdeskrunner.protocol;

/**
 * Runner → qits-projects: the project's desk did not become a running container; {@code detail} is
 * what went wrong, in docker's own last words where there were any.
 */
public record LaunchFailed(String projectId, String detail) implements DeskRunnerMessage {}
