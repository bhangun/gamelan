package tech.kayys.gamelan.engine.collaboration;

/**
 * Isolation boundary used for a participant's work.
 */
public enum ParticipantIsolation {
    UNSPECIFIED,
    NONE,
    PROCESS,
    CONTAINER,
    VIRTUAL_MACHINE,
    SANDBOX
}
