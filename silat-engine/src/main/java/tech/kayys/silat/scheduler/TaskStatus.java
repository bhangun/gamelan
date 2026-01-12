package tech.kayys.silat.scheduler;

public enum TaskStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED;

    boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }
}
