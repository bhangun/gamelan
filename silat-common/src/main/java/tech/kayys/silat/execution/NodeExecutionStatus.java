package tech.kayys.silat.execution;

/**
 * Status of a node execution.
 */
public enum NodeExecutionStatus {
    PENDING, // Waiting to execute
    EXECUTING, // Currently executing
    COMPLETED, // Successfully completed
    FAILED, // Execution failed
    WAITING, // Waiting for external signal
    CANCELLED, // Execution cancelled
    SKIPPED, // Skipped due to conditions
    RETRYING, // Currently retrying
    SUCCESS,
    RUNNING; // Currently executing

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == SKIPPED || this == CANCELLED;
    }
}
