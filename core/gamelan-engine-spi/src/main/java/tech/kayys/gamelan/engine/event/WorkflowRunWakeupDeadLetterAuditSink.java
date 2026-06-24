package tech.kayys.gamelan.engine.event;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupDeadLetterAuditEvent.Operation;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupDeadLetterAuditEvent.Outcome;

/**
 * Extension point for recording operator actions against wake-up dead letters.
 */
public interface WorkflowRunWakeupDeadLetterAuditSink {

    Uni<Void> append(WorkflowRunWakeupDeadLetterAuditEvent event);

    default Uni<List<WorkflowRunWakeupDeadLetterAuditEvent>> entries(AuditQuery query) {
        return Uni.createFrom().item(List.of());
    }

    default Uni<Long> count(AuditQuery query) {
        return entries(query).map(entries -> (long) entries.size());
    }

    default Uni<AuditSummary> summary(AuditQuery query) {
        return entries(query).map(AuditSummary::from);
    }

    default Uni<AuditPurgeResult> purge(AuditPurgePolicy policy) {
        AuditPurgePolicy effectivePolicy = policy != null ? policy : AuditPurgePolicy.disabled();
        return Uni.createFrom().item(AuditPurgeResult.empty(effectivePolicy.dryRun()));
    }

    record AuditQuery(
            int limit,
            Operation operation,
            Outcome outcome,
            String intentId,
            String runId,
            String tenantId,
            Boolean dryRun,
            Instant occurredFrom,
            Instant occurredTo) {

        public AuditQuery {
            limit = limit > 0 ? Math.min(limit, 1000) : 100;
            intentId = normalize(intentId);
            runId = normalize(runId);
            tenantId = normalize(tenantId);
            if (occurredFrom != null && occurredTo != null && occurredFrom.isAfter(occurredTo)) {
                throw new IllegalArgumentException("Audit occurredFrom cannot be after occurredTo");
            }
        }

        public static AuditQuery all() {
            return new AuditQuery(100, null, null, null, null, null, null, null, null);
        }

        public boolean matches(WorkflowRunWakeupDeadLetterAuditEvent event) {
            if (event == null) {
                return false;
            }
            return matches(operation, event.operation())
                    && matches(outcome, event.outcome())
                    && matchesIntent(event)
                    && matchesRun(event)
                    && matchesTenant(event)
                    && matches(dryRun, event.dryRun())
                    && matchesOccurredAt(event.occurredAt());
        }

        private boolean matchesIntent(WorkflowRunWakeupDeadLetterAuditEvent event) {
            return intentId == null
                    || Objects.equals(intentId, event.intentId())
                    || event.intentIds().contains(intentId);
        }

        private boolean matchesRun(WorkflowRunWakeupDeadLetterAuditEvent event) {
            return runId == null
                    || (event.query() != null && Objects.equals(runId, event.query().runId()));
        }

        private boolean matchesTenant(WorkflowRunWakeupDeadLetterAuditEvent event) {
            return tenantId == null
                    || (event.query() != null && Objects.equals(tenantId, event.query().tenantId()));
        }

        private boolean matchesOccurredAt(Instant occurredAt) {
            if (occurredAt == null) {
                return false;
            }
            return (occurredFrom == null || !occurredAt.isBefore(occurredFrom))
                    && (occurredTo == null || !occurredAt.isAfter(occurredTo));
        }

        private static String normalize(String value) {
            return value != null && !value.isBlank() ? value.trim() : null;
        }

        private static boolean matches(Object expected, Object actual) {
            return expected == null || Objects.equals(expected, actual);
        }
    }

    record AuditSummary(
            long totalEvents,
            long selected,
            long succeeded,
            long failed,
            long skipped,
            List<AuditSummaryBucket> buckets) {

        public AuditSummary {
            if (totalEvents < 0 || selected < 0 || succeeded < 0 || failed < 0 || skipped < 0) {
                throw new IllegalArgumentException("Audit summary counters cannot be negative");
            }
            buckets = List.copyOf(buckets != null ? buckets : List.of());
        }

        public static AuditSummary empty() {
            return new AuditSummary(0, 0, 0, 0, 0, List.of());
        }

        public static AuditSummary from(List<WorkflowRunWakeupDeadLetterAuditEvent> events) {
            List<WorkflowRunWakeupDeadLetterAuditEvent> safeEvents = events != null ? events : List.of();
            record AuditBucketKey(Operation operation, Outcome outcome, boolean dryRun) {
            }
            List<AuditSummaryBucket> buckets = safeEvents.stream()
                    .collect(java.util.stream.Collectors.groupingBy(
                            event -> new AuditBucketKey(event.operation(), event.outcome(), event.dryRun()),
                            java.util.stream.Collectors.counting()))
                    .entrySet()
                    .stream()
                    .map(entry -> new AuditSummaryBucket(
                            entry.getKey().operation(),
                            entry.getKey().outcome(),
                            entry.getKey().dryRun(),
                            entry.getValue()))
                    .sorted(java.util.Comparator
                            .comparing(AuditSummaryBucket::operation)
                            .thenComparing(AuditSummaryBucket::outcome)
                            .thenComparing(AuditSummaryBucket::dryRun))
                    .toList();
            return new AuditSummary(
                    safeEvents.size(),
                    safeEvents.stream().mapToLong(WorkflowRunWakeupDeadLetterAuditEvent::selected).sum(),
                    safeEvents.stream().mapToLong(WorkflowRunWakeupDeadLetterAuditEvent::succeeded).sum(),
                    safeEvents.stream().mapToLong(WorkflowRunWakeupDeadLetterAuditEvent::failed).sum(),
                    safeEvents.stream().mapToLong(WorkflowRunWakeupDeadLetterAuditEvent::skipped).sum(),
                    buckets);
        }
    }

    record AuditSummaryBucket(
            Operation operation,
            Outcome outcome,
            boolean dryRun,
            long events) {

        public AuditSummaryBucket {
            Objects.requireNonNull(operation, "Audit summary operation cannot be null");
            Objects.requireNonNull(outcome, "Audit summary outcome cannot be null");
            if (events < 0) {
                throw new IllegalArgumentException("Audit summary bucket event count cannot be negative");
            }
        }
    }

    record AuditPurgePolicy(
            AuditQuery query,
            Duration olderThan,
            int retainLatest,
            boolean dryRun) {

        public AuditPurgePolicy {
            query = query != null ? query : AuditQuery.all();
            if (olderThan != null && olderThan.isNegative()) {
                throw new IllegalArgumentException("Audit purge olderThan cannot be negative");
            }
            retainLatest = retainLatest >= 0 ? retainLatest : -1;
        }

        public static AuditPurgePolicy disabled() {
            return new AuditPurgePolicy(AuditQuery.all(), null, -1, true);
        }

        public boolean hasRetentionCriteria() {
            return olderThan != null || retainLatest >= 0;
        }

        public boolean matchesAge(WorkflowRunWakeupDeadLetterAuditEvent event, Instant now) {
            if (event == null || event.occurredAt() == null) {
                return false;
            }
            return olderThan == null || event.occurredAt().isBefore(now.minus(olderThan));
        }
    }

    record AuditPurgeResult(
            int selected,
            int purged,
            boolean dryRun,
            List<String> auditIds) {

        public AuditPurgeResult {
            if (selected < 0 || purged < 0) {
                throw new IllegalArgumentException("Audit purge counters cannot be negative");
            }
            auditIds = List.copyOf(auditIds != null ? auditIds : List.of());
        }

        public static AuditPurgeResult empty(boolean dryRun) {
            return new AuditPurgeResult(0, 0, dryRun, List.of());
        }
    }
}
