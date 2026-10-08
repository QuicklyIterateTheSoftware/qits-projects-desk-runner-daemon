package eu.wohlben.qits.projectsdeskrunner.protocol;

/**
 * qits-projects → runner: remove this project's desk container <b>and</b> its desk volume, so
 * nothing of the desk is left on the node. Answered {@link Removed}, idempotently.
 */
public record Remove(String projectId) implements DeskRunnerMessage {}
