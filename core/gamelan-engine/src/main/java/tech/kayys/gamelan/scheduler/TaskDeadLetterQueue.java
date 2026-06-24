package tech.kayys.gamelan.scheduler;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.engine.node.NodeExecutionTask;

public interface TaskDeadLetterQueue {

    Uni<Void> publish(DeadLetterTask task);

    default Uni<List<DeadLetterTask>> list(int limit) {
        return Uni.createFrom().item(List.of());
    }

    default Uni<List<DeadLetterTask>> list(DeadLetterQuery query) {
        DeadLetterQuery effectiveQuery = query != null ? query : DeadLetterQuery.all();
        return list(effectiveQuery.limit())
                .map(entries -> entries.stream()
                        .filter(effectiveQuery::matches)
                        .toList());
    }

    default Uni<Long> count() {
        return Uni.createFrom().item(0L);
    }

    default Uni<Long> count(DeadLetterQuery query) {
        return list(query).map(entries -> (long) entries.size());
    }

    default Uni<Optional<DeadLetterTask>> get(String messageId) {
        String normalizedMessageId = normalizeMessageId(messageId);
        if (normalizedMessageId == null) {
            return Uni.createFrom().item(Optional.empty());
        }
        return list(1000)
                .map(entries -> entries.stream()
                        .filter(entry -> Objects.equals(entry.messageId(), normalizedMessageId))
                        .findFirst());
    }

    default Uni<Boolean> delete(String messageId) {
        return Uni.createFrom().item(false);
    }

    default Uni<Long> clear(DeadLetterQuery query) {
        DeadLetterQuery effectiveQuery = query != null ? query : DeadLetterQuery.all();
        return list(new DeadLetterQuery(
                1000,
                effectiveQuery.runId(),
                effectiveQuery.nodeId(),
                effectiveQuery.tenantId(),
                effectiveQuery.reason()))
                .flatMap(this::deleteEntries);
    }

    default Uni<Void> clear() {
        return Uni.createFrom().voidItem();
    }

    private static String normalizeMessageId(String messageId) {
        return messageId != null && !messageId.isBlank() ? messageId.trim() : null;
    }

    record DeadLetterQuery(
            int limit,
            String runId,
            String nodeId,
            String tenantId,
            String reason) {

        public DeadLetterQuery {
            limit = normalizeLimit(limit);
            runId = normalize(runId);
            nodeId = normalize(nodeId);
            tenantId = normalize(tenantId);
            reason = normalize(reason);
        }

        public static DeadLetterQuery all() {
            return new DeadLetterQuery(100, null, null, null, null);
        }

        public boolean hasFilters() {
            return runId != null || nodeId != null || tenantId != null || reason != null;
        }

        public boolean matches(DeadLetterTask deadLetter) {
            if (deadLetter == null || deadLetter.task() == null) {
                return false;
            }
            return matches(runId, deadLetter.task().runId().value())
                    && matches(nodeId, deadLetter.task().nodeId().value())
                    && matches(reason, deadLetter.reason())
                    && matches(tenantId, tenantId(deadLetter));
        }

        private static int normalizeLimit(int limit) {
            return limit > 0 ? Math.min(limit, 1000) : 100;
        }

        private static String normalize(String value) {
            return value != null && !value.isBlank() ? value.trim() : null;
        }

        private static boolean matches(String expected, String actual) {
            return expected == null || Objects.equals(expected, actual);
        }

        private static String tenantId(DeadLetterTask deadLetter) {
            Object value = deadLetter.task().context().get(NodeExecutionTask.TENANT_ID_KEY);
            return value instanceof String text && !text.isBlank() ? text.trim() : null;
        }
    }

    record DeadLetterTask(
            String messageId,
            NodeExecutionTask task,
            String reason,
            int deliveryAttempt,
            int deferCount,
            Instant firstSeenAt,
            Instant deadLetteredAt,
            Map<String, Object> diagnostics) {

        public DeadLetterTask {
            Objects.requireNonNull(messageId, "messageId cannot be null");
            Objects.requireNonNull(task, "task cannot be null");
            reason = reason != null && !reason.isBlank() ? reason.trim() : "unknown";
            deliveryAttempt = Math.max(1, deliveryAttempt);
            deferCount = Math.max(0, deferCount);
            firstSeenAt = firstSeenAt != null ? firstSeenAt : Instant.now();
            deadLetteredAt = deadLetteredAt != null ? deadLetteredAt : Instant.now();
            diagnostics = diagnostics == null || diagnostics.isEmpty()
                    ? Map.of()
                    : Map.copyOf(diagnostics);
        }
    }

    private Uni<Long> deleteEntries(List<DeadLetterTask> entries) {
        Uni<Long> deleted = Uni.createFrom().item(0L);
        for (DeadLetterTask entry : entries) {
            deleted = deleted.flatMap(count -> delete(entry.messageId())
                    .map(removed -> Boolean.TRUE.equals(removed) ? count + 1 : count));
        }
        return deleted;
    }
}
