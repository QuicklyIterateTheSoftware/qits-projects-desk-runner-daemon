package eu.wohlben.qits.projectsdeskrunner.protocol;

import eu.wohlben.qits.runner.protocol.RunnerMessage;

/**
 * Every front-desk frame on the runner's control socket. Sealed here and extending the base's
 * non-sealed {@link RunnerMessage}, because a {@code permits} list cannot span artifacts: the
 * lifecycle frames are qits-runner-protocol's, and {@code RunnerCodec} hands everything else to
 * {@link DeskRunnerCodec}. Each side switches exhaustively over this set for its own frames.
 */
public sealed interface DeskRunnerMessage extends RunnerMessage
    permits Estate,
        Take,
        Remove,
        ProbeLogin,
        HealthCheck,
        Inventory,
        LaunchFailed,
        Removed,
        LoginState,
        HealthChecked {}
