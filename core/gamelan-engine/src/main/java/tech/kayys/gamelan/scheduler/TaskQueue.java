package tech.kayys.gamelan.scheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.engine.node.NodeExecutionTask;

public interface TaskQueue {

    String DELIVERY_ATTEMPT_KEY = TaskQueueMetadata.DELIVERY_ATTEMPT_KEY;
    String DEFER_COUNT_KEY = TaskQueueMetadata.DEFER_COUNT_KEY;
    String FIRST_SEEN_AT_KEY = TaskQueueMetadata.FIRST_SEEN_AT_KEY;
    String LAST_DEFER_REASON_KEY = TaskQueueMetadata.LAST_DEFER_REASON_KEY;

    Uni<Void> enqueue(NodeExecutionTask task);

    Multi<QueuedTask> consume();

    Uni<Void> acknowledge(String messageId);

    default Uni<Void> acknowledge(QueuedTask queuedTask) {
        Objects.requireNonNull(queuedTask, "queuedTask cannot be null");
        return acknowledge(queuedTask.messageId());
    }

    default Uni<Boolean> renewLease(QueuedTask queuedTask, Duration leaseDuration) {
        Objects.requireNonNull(queuedTask, "queuedTask cannot be null");
        return Uni.createFrom().item(false);
    }

    default Uni<QueueStats> stats() {
        return Uni.createFrom().item(QueueStats.unknown());
    }

    default Uni<Void> defer(QueuedTask queuedTask, Duration delay, String reason) {
        Objects.requireNonNull(queuedTask, "queuedTask cannot be null");
        Duration effectiveDelay = delay != null && !delay.isNegative()
                ? delay
                : Duration.ZERO;
        Uni<Void> deferred = Uni.createFrom().voidItem();
        if (!effectiveDelay.isZero()) {
            deferred = deferred.onItem().delayIt().by(effectiveDelay);
        }
        NodeExecutionTask deferredTask = TaskQueueMetadata.deferredTask(queuedTask, reason);
        return deferred
                .flatMap(ignored -> enqueue(deferredTask))
                .flatMap(ignored -> acknowledge(queuedTask));
    }

    static record QueuedTask(String messageId, NodeExecutionTask task, String leaseId, Instant leaseExpiresAt) {

        public QueuedTask(String messageId, NodeExecutionTask task) {
            this(messageId, task, messageId, null);
        }

        public QueuedTask {
            Objects.requireNonNull(messageId, "messageId cannot be null");
            Objects.requireNonNull(task, "task cannot be null");
            messageId = requireText(messageId, "messageId cannot be blank");
            leaseId = leaseId != null && !leaseId.isBlank()
                    ? leaseId.trim()
                    : messageId;
        }

        public int deliveryAttempt() {
            return TaskQueueMetadata.deliveryAttempt(task);
        }

        public int deferCount() {
            return TaskQueueMetadata.deferCount(task);
        }

        public Instant firstSeenAt() {
            return TaskQueueMetadata.firstSeenAt(task);
        }

        public String lastDeferReason() {
            return TaskQueueMetadata.lastDeferReason(task);
        }

        public boolean hasLeaseExpiry() {
            return leaseExpiresAt != null;
        }

        public boolean leaseExpired(Instant now) {
            Instant reference = now != null ? now : Instant.now();
            return leaseExpiresAt != null && !leaseExpiresAt.isAfter(reference);
        }

        public boolean leaseActive(Instant now) {
            return !leaseExpired(now);
        }
    }

    static record QueueStats(
            long total,
            long available,
            long leased,
            long expired,
            long unreadable,
            long claimable,
            boolean known,
            QueueHealth health,
            Instant observedAt) {

        public QueueStats {
            observedAt = observedAt != null ? observedAt : Instant.now();
            if (known) {
                requireNonNegative(total, "total");
                requireNonNegative(available, "available");
                requireNonNegative(leased, "leased");
                requireNonNegative(expired, "expired");
                requireNonNegative(unreadable, "unreadable");
                claimable = available + expired;
                health = QueueHealth.fromCounts(available, leased, expired, unreadable);
            } else {
                total = -1;
                available = -1;
                leased = -1;
                expired = -1;
                unreadable = -1;
                claimable = -1;
                health = QueueHealth.UNKNOWN;
            }
        }

        public static QueueStats known(long total, long available, long leased, long expired, long unreadable) {
            return knownAt(Instant.now(), total, available, leased, expired, unreadable);
        }

        public static QueueStats knownAt(
                Instant observedAt,
                long total,
                long available,
                long leased,
                long expired,
                long unreadable) {
            return new QueueStats(
                    total,
                    available,
                    leased,
                    expired,
                    unreadable,
                    available + expired,
                    true,
                    QueueHealth.IDLE,
                    observedAt);
        }

        public static QueueStats unknown() {
            return unknownAt(Instant.now());
        }

        public static QueueStats unknownAt(Instant observedAt) {
            return new QueueStats(-1, -1, -1, -1, -1, -1, false, QueueHealth.UNKNOWN, observedAt);
        }

        private static void requireNonNegative(long value, String name) {
            if (value < 0) {
                throw new IllegalArgumentException(name + " cannot be negative");
            }
        }
    }

    enum QueueHealth {
        UNKNOWN,
        IDLE,
        ACTIVE,
        BACKLOG,
        STALE_LEASES,
        UNREADABLE_RECORDS;

        private static QueueHealth fromCounts(long available, long leased, long expired, long unreadable) {
            if (unreadable > 0) {
                return UNREADABLE_RECORDS;
            }
            if (expired > 0) {
                return STALE_LEASES;
            }
            if (available > 0) {
                return BACKLOG;
            }
            if (leased > 0) {
                return ACTIVE;
            }
            return IDLE;
        }
    }

    static NodeExecutionTask withoutQueueMetadata(NodeExecutionTask task) {
        return TaskQueueMetadata.withoutQueueMetadata(task);
    }

    private static String requireText(String value, String message) {
        String normalized = value.trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return normalized;
    }
}
