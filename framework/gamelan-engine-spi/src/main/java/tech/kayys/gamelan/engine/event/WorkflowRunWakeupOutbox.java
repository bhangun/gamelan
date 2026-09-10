package tech.kayys.gamelan.engine.event;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import io.smallrye.mutiny.Uni;

/**
 * Stores level-triggered workflow run wake-up intents before transport delivery.
 *
 * Runtime profiles can back this with memory, files, PostgreSQL, Redis, Kafka,
 * or another broker-specific outbox without changing engine state transitions.
 */
public interface WorkflowRunWakeupOutbox {

    Uni<WorkflowRunWakeupIntent> enqueue(WorkflowRunUpdateEvent event);

    /**
     * Returns a read-only snapshot of queued wake-ups for observability.
     */
    Uni<List<WorkflowRunWakeupIntent>> pending(int maxItems);

    /**
     * Claims wake-ups for delivery.
     *
     * Durable outboxes should override this method to lease rows/records before
     * returning them. In-memory/local outboxes can use the read-only snapshot
     * because delivery is process-local.
     */
    default Uni<List<WorkflowRunWakeupIntent>> claimPending(int maxItems) {
        return pending(maxItems);
    }

    default Uni<List<WorkflowRunWakeupDeadLetter>> deadLetters(int maxItems) {
        return Uni.createFrom().item(List.of());
    }

    default Uni<List<WorkflowRunWakeupDeadLetter>> deadLetters(DeadLetterQuery query) {
        DeadLetterQuery effectiveQuery = query != null ? query : DeadLetterQuery.all();
        return deadLetters(effectiveQuery.limit())
                .map(entries -> entries.stream()
                        .filter(effectiveQuery::matches)
                        .toList());
    }

    default Uni<Long> deadLetterCount() {
        return Uni.createFrom().item(0L);
    }

    default Uni<Long> deadLetterCount(DeadLetterQuery query) {
        return deadLetters(query).map(entries -> (long) entries.size());
    }

    default Uni<Optional<WorkflowRunWakeupIntent>> replayDeadLetter(String intentId) {
        return Uni.createFrom().item(Optional.<WorkflowRunWakeupIntent>empty());
    }

    default Uni<Boolean> deleteDeadLetter(String intentId) {
        return Uni.createFrom().item(false);
    }

    default Uni<DeadLetterPurgeResult> purgeDeadLetters(DeadLetterPurgePolicy policy) {
        DeadLetterPurgePolicy effectivePolicy = policy != null ? policy : DeadLetterPurgePolicy.disabled();
        return Uni.createFrom().item(DeadLetterPurgeResult.empty(effectivePolicy.dryRun()));
    }

    default Uni<Void> markDelivered(String intentId) {
        return markDelivered(intentId, null);
    }

    Uni<Void> markDelivered(String intentId, WorkflowRunUpdateEvent deliveredEvent);

    Uni<Void> markFailed(String intentId, Throwable error);

    record DeadLetterQuery(
            int limit,
            String runId,
            String tenantId,
            String reason,
            String deadLetterReason) {

        public DeadLetterQuery {
            limit = limit > 0 ? Math.min(limit, 1000) : 100;
            runId = normalize(runId);
            tenantId = normalize(tenantId);
            reason = normalize(reason);
            deadLetterReason = normalize(deadLetterReason);
        }

        public static DeadLetterQuery all() {
            return new DeadLetterQuery(100, null, null, null, null);
        }

        public boolean hasFilters() {
            return runId != null || tenantId != null || reason != null || deadLetterReason != null;
        }

        public boolean matches(WorkflowRunWakeupDeadLetter deadLetter) {
            if (deadLetter == null || deadLetter.event() == null) {
                return false;
            }
            return matches(runId, deadLetter.event().runId())
                    && matches(tenantId, deadLetter.event().tenantId())
                    && matches(reason, deadLetter.event().reason())
                    && matches(deadLetterReason, deadLetter.deadLetterReason());
        }

        private static String normalize(String value) {
            return value != null && !value.isBlank() ? value.trim() : null;
        }

        private static boolean matches(String expected, String actual) {
            return expected == null || Objects.equals(expected, actual);
        }
    }

    record DeadLetterPurgePolicy(
            DeadLetterQuery query,
            Duration olderThan,
            int retainLatest,
            boolean dryRun) {

        public DeadLetterPurgePolicy {
            query = query != null ? query : DeadLetterQuery.all();
            if (olderThan != null && olderThan.isNegative()) {
                throw new IllegalArgumentException("Dead-letter purge olderThan cannot be negative");
            }
            retainLatest = retainLatest >= 0 ? retainLatest : -1;
        }

        public static DeadLetterPurgePolicy disabled() {
            return new DeadLetterPurgePolicy(DeadLetterQuery.all(), null, -1, true);
        }

        public boolean hasRetentionCriteria() {
            return olderThan != null || retainLatest >= 0;
        }

        public boolean matchesAge(WorkflowRunWakeupDeadLetter deadLetter, Instant now) {
            if (deadLetter == null || deadLetter.deadLetteredAt() == null) {
                return false;
            }
            return olderThan == null || deadLetter.deadLetteredAt().isBefore(now.minus(olderThan));
        }
    }

    record DeadLetterPurgeResult(
            int selected,
            int purged,
            boolean dryRun,
            List<String> intentIds) {

        public DeadLetterPurgeResult {
            if (selected < 0) {
                throw new IllegalArgumentException("Dead-letter purge selected count cannot be negative");
            }
            if (purged < 0) {
                throw new IllegalArgumentException("Dead-letter purge purged count cannot be negative");
            }
            intentIds = List.copyOf(intentIds != null ? intentIds : List.of());
        }

        public static DeadLetterPurgeResult empty(boolean dryRun) {
            return new DeadLetterPurgeResult(0, 0, dryRun, List.of());
        }
    }
}
