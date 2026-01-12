package tech.kayys.silat.model;

/**
 * 🔒 Workflow run state machine.
 * ONLY WorkflowRunManager can transition states.
 */
public enum WorkflowRunState {
    CREATED, // Run created but not started
    RUNNING, // Actively executing nodes
    WAITING, // Waiting for external signal
    RETRYING, // Preparing to retry a failed node
    COMPENSATING, // Rolling back due to failure
    COMPLETED, // Successful completion
    FAILED, // Terminal failure state
    CANCELLED; // Explicitly cancelled

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }
}