package tech.kayys.gamelan.workflow;

import java.util.LinkedHashMap;
import java.util.Map;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import tech.kayys.gamelan.engine.executor.ExecutorInfo;
import tech.kayys.gamelan.engine.executor.ExecutorSelectionRejectionReasons;
import tech.kayys.gamelan.engine.node.NodeDefinition;
import tech.kayys.gamelan.registry.ExecutorSelectionReport;

/**
 * Centralizes executor-admission policy for workflow orchestration.
 */
@ApplicationScoped
public class TaskAdmissionController {

    static final String POLICY_FAIL = "fail";
    static final String POLICY_REJECT = "reject";
    static final String POLICY_WAIT = "wait";
    static final String POLICY_DEAD_LETTER = "dead-letter";

    private static final String REASON_EXECUTOR_SELECTED = "executor-selected";
    private static final String DIAGNOSTIC_NODE_ID = "nodeId";
    private static final String DIAGNOSTIC_EXECUTOR_TYPE = "executorType";
    private static final String DIAGNOSTIC_SELECTED_EXECUTOR_ID = "selectedExecutorId";
    private static final String DIAGNOSTIC_SELECTION_REASON = "selectionReason";
    private static final String DIAGNOSTIC_PERMANENT_SELECTION_FAILURE = "permanentSelectionFailure";
    private static final String DIAGNOSTIC_SELECTION = "selection";

    @Inject
    MeterRegistry meterRegistry;

    public boolean shouldResolveBeforeReservation(String policy) {
        return POLICY_WAIT.equals(normalizePolicy(policy));
    }

    public TaskAdmissionDecision executorSelected(
            NodeDefinition node,
            ExecutorInfo executor,
            ExecutorSelectionReport report,
            String policy) {

        Map<String, Object> diagnostics = baseDiagnostics(node, report);
        if (executor != null) {
            diagnostics.put(DIAGNOSTIC_SELECTED_EXECUTOR_ID, executor.executorId());
        }
        return new TaskAdmissionDecision(
                TaskAdmissionAction.DISPATCH,
                REASON_EXECUTOR_SELECTED,
                normalizePolicy(policy),
                diagnostics);
    }

    public TaskAdmissionDecision noExecutor(
            NodeDefinition node,
            ExecutorSelectionReport report,
            String policy) {

        String normalizedPolicy = normalizePolicy(policy);
        String reason = report != null
                ? report.primaryRejectionReason()
                : ExecutorSelectionRejectionReasons.NO_EXECUTOR;
        TaskAdmissionAction action = actionForMissingExecutor(normalizedPolicy, reason);
        return new TaskAdmissionDecision(
                action,
                reason,
                normalizedPolicy,
                baseDiagnostics(node, report));
    }

    public void record(TaskAdmissionDecision decision) {
        MeterRegistry registry = meterRegistry;
        if (registry == null || decision == null) {
            return;
        }
        Counter.builder("gamelan.orchestrator.admission.decisions")
                .description("Workflow orchestrator executor-admission decisions")
                .tag("action", decision.action().metricName())
                .tag("reason", decision.reason())
                .tag("policy", decision.policy())
                .register(registry)
                .increment();
    }

    private static TaskAdmissionAction actionForMissingExecutor(String policy, String reason) {
        if (POLICY_WAIT.equals(policy)) {
            return ExecutorSelectionRejectionReasons.CAPACITY_SATURATED.equals(reason)
                    ? TaskAdmissionAction.DEFER_CAPACITY
                    : TaskAdmissionAction.WAIT_FOR_EXECUTOR;
        }
        if (POLICY_DEAD_LETTER.equals(policy)) {
            return TaskAdmissionAction.DEAD_LETTER;
        }
        return TaskAdmissionAction.REJECT;
    }

    private static Map<String, Object> baseDiagnostics(
            NodeDefinition node,
            ExecutorSelectionReport report) {

        Map<String, Object> diagnostics = new LinkedHashMap<>();
        if (node != null) {
            diagnostics.put(DIAGNOSTIC_NODE_ID, node.id().value());
            if (node.executorType() != null && !node.executorType().isBlank()) {
                diagnostics.put(DIAGNOSTIC_EXECUTOR_TYPE, node.executorType());
            }
        }
        if (report != null) {
            diagnostics.put(DIAGNOSTIC_SELECTION_REASON, report.primaryRejectionReason());
            diagnostics.put(DIAGNOSTIC_PERMANENT_SELECTION_FAILURE, report.hasPermanentRejection());
            diagnostics.put(DIAGNOSTIC_SELECTION, report.toErrorContext());
        }
        return diagnostics;
    }

    private static String normalizePolicy(String policy) {
        if (policy == null || policy.isBlank()) {
            return POLICY_FAIL;
        }
        String normalized = policy.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (normalized) {
            case POLICY_WAIT -> POLICY_WAIT;
            case POLICY_REJECT -> POLICY_REJECT;
            case POLICY_DEAD_LETTER, "dead_letter", "deadletter" -> POLICY_DEAD_LETTER;
            default -> POLICY_FAIL;
        };
    }
}
