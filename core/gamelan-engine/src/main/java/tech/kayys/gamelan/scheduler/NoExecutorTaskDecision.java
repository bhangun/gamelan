package tech.kayys.gamelan.scheduler;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import tech.kayys.gamelan.engine.executor.ExecutorSelectionRejectionReasons;
import tech.kayys.gamelan.registry.ExecutorSelectionReport;

/**
 * Captures the worker decision when no compatible executor is available.
 */
final class NoExecutorTaskDecision {

    static final String ACTION_DEFER = "defer";
    static final String ACTION_DEAD_LETTER = "dead-letter";
    static final String DIAGNOSTIC_WORKER_DECISION = "workerDecision";
    static final String DIAGNOSTIC_SELECTION_REASON = "selectionReason";
    static final String DIAGNOSTIC_PERMANENT_SELECTION_FAILURE = "permanentSelectionFailure";
    static final String DIAGNOSTIC_DEFER_COUNT = "deferCount";
    static final String DIAGNOSTIC_MAX_DEFERS = "maxDefers";
    static final String DIAGNOSTIC_DEFER_BUDGET_EXHAUSTED = "deferBudgetExhausted";
    static final String DIAGNOSTIC_DELIVERY_ATTEMPT = "deliveryAttempt";

    private final String action;
    private final String reason;
    private final boolean permanentSelectionFailure;
    private final boolean deferBudgetExhausted;
    private final int deferCount;
    private final int maxDefers;
    private final int deliveryAttempt;

    private NoExecutorTaskDecision(
            String action,
            String reason,
            boolean permanentSelectionFailure,
            boolean deferBudgetExhausted,
            int deferCount,
            int maxDefers,
            int deliveryAttempt) {
        this.action = action;
        this.reason = reason;
        this.permanentSelectionFailure = permanentSelectionFailure;
        this.deferBudgetExhausted = deferBudgetExhausted;
        this.deferCount = deferCount;
        this.maxDefers = maxDefers;
        this.deliveryAttempt = deliveryAttempt;
    }

    static NoExecutorTaskDecision evaluate(
            TaskQueue.QueuedTask queuedTask,
            ExecutorSelectionReport report,
            int configuredMaxDefers) {
        Objects.requireNonNull(queuedTask, "queuedTask cannot be null");
        String reason = report != null
                ? report.primaryRejectionReason()
                : ExecutorSelectionRejectionReasons.NO_EXECUTOR;
        boolean permanent = report != null
                ? report.hasPermanentRejection()
                : ExecutorSelectionRejectionReasons.isPermanent(reason);
        int deferCount = queuedTask.deferCount();
        int maxDefers = Math.max(0, configuredMaxDefers);
        boolean exhausted = deferCount >= maxDefers;
        String action = permanent || exhausted ? ACTION_DEAD_LETTER : ACTION_DEFER;
        return new NoExecutorTaskDecision(
                action,
                reason,
                permanent,
                exhausted,
                deferCount,
                maxDefers,
                queuedTask.deliveryAttempt());
    }

    boolean shouldDeadLetter() {
        return ACTION_DEAD_LETTER.equals(action);
    }

    String action() {
        return action;
    }

    String reason() {
        return reason;
    }

    boolean permanentSelectionFailure() {
        return permanentSelectionFailure;
    }

    boolean deferBudgetExhausted() {
        return deferBudgetExhausted;
    }

    int deferCount() {
        return deferCount;
    }

    int maxDefers() {
        return maxDefers;
    }

    Map<String, Object> diagnostics(ExecutorSelectionReport report) {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        if (report != null) {
            diagnostics.putAll(report.toErrorContext());
        }
        diagnostics.put(DIAGNOSTIC_WORKER_DECISION, action);
        diagnostics.put(DIAGNOSTIC_SELECTION_REASON, reason);
        diagnostics.put(DIAGNOSTIC_PERMANENT_SELECTION_FAILURE, permanentSelectionFailure);
        diagnostics.put(DIAGNOSTIC_DEFER_COUNT, deferCount);
        diagnostics.put(DIAGNOSTIC_MAX_DEFERS, maxDefers);
        diagnostics.put(DIAGNOSTIC_DEFER_BUDGET_EXHAUSTED, deferBudgetExhausted);
        diagnostics.put(DIAGNOSTIC_DELIVERY_ATTEMPT, deliveryAttempt);
        return Map.copyOf(diagnostics);
    }
}
