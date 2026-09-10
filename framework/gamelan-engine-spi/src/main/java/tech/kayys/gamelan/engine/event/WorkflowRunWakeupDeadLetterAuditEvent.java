package tech.kayys.gamelan.engine.event;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import tech.kayys.gamelan.engine.event.WorkflowRunWakeupOutbox.DeadLetterQuery;

/**
 * Audit envelope for operator actions against workflow wake-up dead letters.
 */
public record WorkflowRunWakeupDeadLetterAuditEvent(
        Operation operation,
        Outcome outcome,
        String intentId,
        DeadLetterQuery query,
        int selected,
        int succeeded,
        int failed,
        int skipped,
        boolean dryRun,
        List<String> intentIds,
        String error,
        Instant occurredAt) {

    public WorkflowRunWakeupDeadLetterAuditEvent {
        operation = Objects.requireNonNull(operation, "Dead-letter audit operation cannot be null");
        outcome = Objects.requireNonNull(outcome, "Dead-letter audit outcome cannot be null");
        intentId = normalize(intentId);
        if (selected < 0 || succeeded < 0 || failed < 0 || skipped < 0) {
            throw new IllegalArgumentException("Dead-letter audit counters cannot be negative");
        }
        intentIds = List.copyOf(intentIds != null ? intentIds : List.of());
        error = normalize(error);
        occurredAt = Objects.requireNonNull(occurredAt, "Dead-letter audit occurredAt cannot be null");
    }

    public static WorkflowRunWakeupDeadLetterAuditEvent single(
            Operation operation,
            String intentId,
            Outcome outcome,
            String error) {
        boolean succeeded = Outcome.SUCCEEDED.equals(outcome);
        return new WorkflowRunWakeupDeadLetterAuditEvent(
                operation,
                outcome,
                intentId,
                null,
                1,
                succeeded ? 1 : 0,
                succeeded ? 0 : 1,
                0,
                false,
                intentId != null && !intentId.isBlank() ? List.of(intentId.trim()) : List.of(),
                error,
                Instant.now());
    }

    public static WorkflowRunWakeupDeadLetterAuditEvent bulk(
            Operation operation,
            DeadLetterQuery query,
            int selected,
            int succeeded,
            int failed,
            int skipped,
            boolean dryRun,
            List<String> intentIds,
            String error) {
        return new WorkflowRunWakeupDeadLetterAuditEvent(
                operation,
                outcome(succeeded, failed, dryRun),
                null,
                query,
                selected,
                succeeded,
                failed,
                skipped,
                dryRun,
                intentIds,
                error,
                Instant.now());
    }

    private static Outcome outcome(int succeeded, int failed, boolean dryRun) {
        if (failed > 0 && succeeded > 0) {
            return Outcome.PARTIAL;
        }
        if (failed > 0) {
            return Outcome.FAILED;
        }
        if (dryRun) {
            return Outcome.DRY_RUN;
        }
        return Outcome.SUCCEEDED;
    }

    private static String normalize(String value) {
        return value != null && !value.isBlank() ? value.trim() : null;
    }

    public enum Operation {
        REPLAY,
        BULK_REPLAY,
        DELETE,
        BULK_DELETE,
        PURGE
    }

    public enum Outcome {
        SUCCEEDED,
        FAILED,
        PARTIAL,
        DRY_RUN
    }
}
