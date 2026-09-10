package tech.kayys.gamelan.scheduler;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import tech.kayys.gamelan.engine.node.NodeExecutionTask;

/**
 * Canonical queue-delivery metadata keys and mutation helpers.
 */
public final class TaskQueueMetadata {

    public static final String DELIVERY_ATTEMPT_KEY = "__queue_delivery_attempt__";
    public static final String DEFER_COUNT_KEY = "__queue_defer_count__";
    public static final String FIRST_SEEN_AT_KEY = "__queue_first_seen_at__";
    public static final String LAST_DEFER_REASON_KEY = "__queue_last_defer_reason__";

    private TaskQueueMetadata() {
    }

    public static int deliveryAttempt(NodeExecutionTask task) {
        return positiveInt(contextValue(task, DELIVERY_ATTEMPT_KEY), 1);
    }

    public static int deferCount(NodeExecutionTask task) {
        return Math.max(0, positiveInt(contextValue(task, DEFER_COUNT_KEY), 0));
    }

    public static Instant firstSeenAt(NodeExecutionTask task) {
        return normalizeFirstSeenAt(contextValue(task, FIRST_SEEN_AT_KEY), Instant.now());
    }

    public static String lastDeferReason(NodeExecutionTask task) {
        Object value = contextValue(task, LAST_DEFER_REASON_KEY);
        return value instanceof String text && !text.isBlank() ? text.trim() : "";
    }

    public static NodeExecutionTask deferredTask(TaskQueue.QueuedTask queuedTask, String reason) {
        Objects.requireNonNull(queuedTask, "queuedTask cannot be null");
        NodeExecutionTask task = queuedTask.task();
        Map<String, Object> context = new HashMap<>(task.context());
        context.put(DELIVERY_ATTEMPT_KEY, queuedTask.deliveryAttempt() + 1);
        context.put(DEFER_COUNT_KEY, queuedTask.deferCount() + 1);
        context.put(FIRST_SEEN_AT_KEY, queuedTask.firstSeenAt().toString());
        if (reason != null && !reason.isBlank()) {
            context.put(LAST_DEFER_REASON_KEY, reason.trim());
        }
        return copyWithContext(task, context);
    }

    public static NodeExecutionTask deliveryTask(NodeExecutionTask task, Instant firstSeenAt) {
        Objects.requireNonNull(task, "task cannot be null");
        Instant effectiveFirstSeenAt = firstSeenAt != null ? firstSeenAt : Instant.now();
        Map<String, Object> context = new HashMap<>(task.context());
        context.put(DELIVERY_ATTEMPT_KEY, deliveryAttempt(task));
        context.put(DEFER_COUNT_KEY, deferCount(task));
        context.put(FIRST_SEEN_AT_KEY, normalizeFirstSeenAt(
                context.get(FIRST_SEEN_AT_KEY),
                effectiveFirstSeenAt).toString());
        String lastDeferReason = lastDeferReason(task);
        if (!lastDeferReason.isBlank()) {
            context.put(LAST_DEFER_REASON_KEY, lastDeferReason);
        }
        return copyWithContext(task, context);
    }

    public static NodeExecutionTask redeliveredTask(TaskQueue.QueuedTask queuedTask) {
        Objects.requireNonNull(queuedTask, "queuedTask cannot be null");
        NodeExecutionTask task = queuedTask.task();
        Map<String, Object> context = new HashMap<>(task.context());
        context.put(DELIVERY_ATTEMPT_KEY, queuedTask.deliveryAttempt() + 1);
        context.put(DEFER_COUNT_KEY, queuedTask.deferCount());
        context.put(FIRST_SEEN_AT_KEY, queuedTask.firstSeenAt().toString());
        String lastDeferReason = queuedTask.lastDeferReason();
        if (!lastDeferReason.isBlank()) {
            context.put(LAST_DEFER_REASON_KEY, lastDeferReason);
        }
        return copyWithContext(task, context);
    }

    public static NodeExecutionTask withoutQueueMetadata(NodeExecutionTask task) {
        Objects.requireNonNull(task, "task cannot be null");
        Map<String, Object> context = new HashMap<>(task.context());
        context.remove(DELIVERY_ATTEMPT_KEY);
        context.remove(DEFER_COUNT_KEY);
        context.remove(FIRST_SEEN_AT_KEY);
        context.remove(LAST_DEFER_REASON_KEY);
        return copyWithContext(task, context);
    }

    private static NodeExecutionTask copyWithContext(NodeExecutionTask task, Map<String, Object> context) {
        return new NodeExecutionTask(
                task.runId(),
                task.nodeId(),
                task.attempt(),
                task.token(),
                context,
                task.retryPolicy());
    }

    private static Object contextValue(NodeExecutionTask task, String key) {
        Objects.requireNonNull(task, "task cannot be null");
        return task.context().get(key);
    }

    private static Instant normalizeFirstSeenAt(Object value, Instant fallback) {
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Instant.parse(text.trim());
            } catch (RuntimeException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static int positiveInt(Object value, int fallback) {
        if (value instanceof Number number) {
            int parsed = number.intValue();
            return parsed > 0 ? parsed : fallback;
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                int parsed = Integer.parseInt(text.trim());
                return parsed > 0 ? parsed : fallback;
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }
}
