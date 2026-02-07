package tech.kayys.gamelan.engine.run;

/**
 * Workflow Run Status - State machine states
 */
public enum RunStatus {
    CREATED, // Initial state
    PENDING, // Queued for execution
    RUNNING, // Currently executing
    SUSPENDED, // Paused waiting for signal
    COMPLETED, // Successfully finished
    FAILED, // Execution failed
    CANCELLED, // Manually cancelled
    COMPENSATING, // Running compensation logic
    COMPENSATED; // Compensation completed

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED || this == COMPENSATED;
    }

    public boolean isActive() {
        return this == RUNNING || this == PENDING || this == COMPENSATING;
    }

    public boolean canTransitionTo(RunStatus target) {
        return switch (this) {
            case CREATED -> target == PENDING || target == CANCELLED;
            case PENDING -> target == RUNNING || target == CANCELLED;
            case RUNNING -> target == SUSPENDED || target == COMPLETED ||
                    target == FAILED || target == CANCELLED || target == COMPENSATING;
            case SUSPENDED -> target == RUNNING || target == CANCELLED || target == FAILED;
            case COMPENSATING -> target == COMPENSATED || target == FAILED;
            case COMPLETED, FAILED, CANCELLED, COMPENSATED -> false;
            default -> false;
        };
    }
}