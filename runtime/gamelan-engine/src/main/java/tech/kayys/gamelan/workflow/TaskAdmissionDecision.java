package tech.kayys.gamelan.workflow;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable admission decision for a node dispatch attempt.
 */
public record TaskAdmissionDecision(
        TaskAdmissionAction action,
        String reason,
        String policy,
        Map<String, Object> diagnostics) {

    public static final String DIAGNOSTIC_ACTION = "admissionAction";
    public static final String DIAGNOSTIC_REASON = "admissionReason";
    public static final String DIAGNOSTIC_POLICY = "admissionPolicy";

    public TaskAdmissionDecision {
        action = Objects.requireNonNull(action, "TaskAdmissionAction cannot be null");
        reason = normalize(reason, "unknown");
        policy = normalize(policy, "fail");
        diagnostics = immutableMap(diagnostics);
    }

    public boolean shouldDispatch() {
        return action == TaskAdmissionAction.DISPATCH;
    }

    public boolean shouldLeavePending() {
        return action == TaskAdmissionAction.WAIT_FOR_EXECUTOR
                || action == TaskAdmissionAction.DEFER_CAPACITY;
    }

    public boolean shouldReject() {
        return action == TaskAdmissionAction.REJECT
                || action == TaskAdmissionAction.DEAD_LETTER;
    }

    public Map<String, Object> toErrorContext() {
        Map<String, Object> context = new LinkedHashMap<>(diagnostics);
        context.put(DIAGNOSTIC_ACTION, action.metricName());
        context.put(DIAGNOSTIC_REASON, reason);
        context.put(DIAGNOSTIC_POLICY, policy);
        return immutableMap(context);
    }

    private static String normalize(String value, String fallback) {
        return value != null && !value.isBlank() ? value.trim() : fallback;
    }

    private static Map<String, Object> immutableMap(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
