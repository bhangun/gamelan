package tech.kayys.gamelan.engine.event;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Durable intent to notify orchestration drivers that a workflow run should be
 * re-evaluated.
 */
public record WorkflowRunWakeupIntent(
        String id,
        WorkflowRunUpdateEvent event,
        int attempts,
        Instant createdAt,
        Instant lastAttemptAt,
        String lastError,
        Instant deliveredAt) {

    public WorkflowRunWakeupIntent {
        Objects.requireNonNull(id, "Wake-up intent id cannot be null");
        if (id.isBlank()) {
            throw new IllegalArgumentException("Wake-up intent id cannot be blank");
        }
        id = id.trim();
        Objects.requireNonNull(event, "WorkflowRunUpdateEvent cannot be null");
        if (attempts < 0) {
            throw new IllegalArgumentException("Wake-up attempts cannot be negative");
        }
        createdAt = Objects.requireNonNull(createdAt, "Wake-up createdAt cannot be null");
        lastError = lastError != null && !lastError.isBlank() ? lastError.trim() : null;
    }

    public static WorkflowRunWakeupIntent pending(WorkflowRunUpdateEvent event, Instant createdAt) {
        return new WorkflowRunWakeupIntent(
                UUID.randomUUID().toString(),
                event,
                0,
                createdAt,
                null,
                null,
                null);
    }

    public boolean delivered() {
        return deliveredAt != null;
    }

    public WorkflowRunWakeupIntent replaceWith(WorkflowRunUpdateEvent nextEvent) {
        return new WorkflowRunWakeupIntent(
                UUID.randomUUID().toString(),
                nextEvent,
                attempts,
                createdAt,
                lastAttemptAt,
                lastError,
                null);
    }

    public WorkflowRunWakeupIntent markDelivered(Instant deliveredAt) {
        return new WorkflowRunWakeupIntent(
                id,
                event,
                attempts,
                createdAt,
                lastAttemptAt,
                lastError,
                Objects.requireNonNull(deliveredAt, "Wake-up deliveredAt cannot be null"));
    }

    public WorkflowRunWakeupIntent markFailed(Throwable error, Instant attemptedAt) {
        Objects.requireNonNull(error, "Wake-up delivery error cannot be null");
        Objects.requireNonNull(attemptedAt, "Wake-up attemptedAt cannot be null");
        return new WorkflowRunWakeupIntent(
                id,
                event,
                attempts + 1,
                createdAt,
                attemptedAt,
                error.getClass().getName() + ": " + error.getMessage(),
                null);
    }
}
