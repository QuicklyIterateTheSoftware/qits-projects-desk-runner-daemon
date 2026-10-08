package eu.wohlben.qits.projectsdeskrunner.protocol;

/** Runner → qits-projects: the answer to {@link Remove}; nothing of the desk is on the node. */
public record Removed(String projectId) implements DeskRunnerMessage {}
