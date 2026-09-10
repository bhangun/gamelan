package tech.kayys.gamelan.engine.event;

import java.time.Instant;
import java.util.Objects;

/**
 * Quarantined wake-up intent that exceeded the delivery attempt budget.
 */
public record WorkflowRunWakeupDeadLetter(
        String intentId,
        WorkflowRunUpdateEvent event,
        String deadLetterReason,
        int attempts,
        Instant createdAt,
        Instant lastAttemptAt,
        String lastError,
        Instant deadLetteredAt) {

    public WorkflowRunWakeupDeadLetter {
        Objects.requireNonNull(intentId, "Wake-up intent id cannot be null");
        if (intentId.isBlank()) {
            throw new IllegalArgumentException("Wake-up intent id cannot be blank");
        }
        intentId = intentId.trim();
        Objects.requireNonNull(event, "WorkflowRunUpdateEvent cannot be null");
        deadLetterReason = deadLetterReason != null && !deadLetterReason.isBlank()
                ? deadLetterReason.trim()
                : "unknown";
        if (attempts < 0) {
            throw new IllegalArgumentException("Wake-up attempts cannot be negative");
        }
        createdAt = Objects.requireNonNull(createdAt, "Wake-up createdAt cannot be null");
        deadLetteredAt = Objects.requireNonNull(deadLetteredAt, "Wake-up deadLetteredAt cannot be null");
        lastError = lastError != null && !lastError.isBlank() ? lastError.trim() : null;
    }

    public static WorkflowRunWakeupDeadLetter fromIntent(
            WorkflowRunWakeupIntent intent,
            String deadLetterReason,
            Instant deadLetteredAt) {
        Objects.requireNonNull(intent, "Wake-up intent cannot be null");
        return new WorkflowRunWakeupDeadLetter(
                intent.id(),
                intent.event(),
                deadLetterReason,
                intent.attempts(),
                intent.createdAt(),
                intent.lastAttemptAt(),
                intent.lastError(),
                deadLetteredAt);
    }
}
