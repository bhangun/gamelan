package tech.kayys.silat.scheduler;

import java.time.Instant;

import tech.kayys.silat.execution.NodeExecutionTask;

/**
 * Scheduled Task
 */
class ScheduledTask {
    private final String taskId;
    private final NodeExecutionTask task;
    private final Instant scheduledAt;
    private final int retryCount;
    private TaskStatus status;
    private Instant completedAt;
    private Throwable error;

    ScheduledTask(
            String taskId,
            NodeExecutionTask task,
            Instant scheduledAt,
            int retryCount,
            TaskStatus status) {
        this.taskId = taskId;
        this.task = task;
        this.scheduledAt = scheduledAt;
        this.retryCount = retryCount;
        this.status = status;
    }

    int retryCount() {
        return retryCount;
    }

    Instant completedAt() {
        return completedAt;
    }

    void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    Throwable error() {
        return error;
    }

    void setError(Throwable error) {
        this.error = error;
    }

    void setStatus(TaskStatus status) {
        this.status = status;
    }

    void markRunning() {
        this.status = TaskStatus.RUNNING;
    }

    void markCompleted() {
        this.status = TaskStatus.COMPLETED;
        this.completedAt = Instant.now();
    }

    void markFailed(Throwable error) {
        this.status = TaskStatus.FAILED;
        this.completedAt = Instant.now();
        this.error = error;
    }

    void markCancelled() {
        this.status = TaskStatus.CANCELLED;
        this.completedAt = Instant.now();
    }

    String taskId() {
        return taskId;
    }

    NodeExecutionTask task() {
        return task;
    }

    Instant scheduledAt() {
        return scheduledAt;
    }

    TaskStatus status() {
        return status;
    }
}
