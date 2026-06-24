package tech.kayys.gamelan.engine.node;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import tech.kayys.gamelan.engine.collaboration.CollaborationContext;
import tech.kayys.gamelan.engine.error.ErrorCode;
import tech.kayys.gamelan.engine.error.GamelanException;
import tech.kayys.gamelan.engine.execution.ExecutionToken;
import tech.kayys.gamelan.engine.executor.ExecutorSelectionPolicy;
import tech.kayys.gamelan.engine.payload.ExecutionPayloads;
import tech.kayys.gamelan.engine.run.RetryPolicy;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;

/**
 * Node Execution Task - Task scheduled for execution
 */
public record NodeExecutionTask(
        WorkflowRunId runId,
        NodeId nodeId,
        int attempt,
        ExecutionToken token,
        Map<String, Object> context,
        RetryPolicy retryPolicy) {

    public NodeExecutionTask {
        Objects.requireNonNull(runId, "WorkflowRunId cannot be null");
        Objects.requireNonNull(nodeId, "NodeId cannot be null");
        validateAttempt(attempt);
        context = ExecutionPayloads.immutableMap(context);
        if (token != null) {
            validateTokenIdentity(runId, nodeId, attempt, token);
            validateTokenTenant(context, token);
        }
        retryPolicy = retryPolicy != null ? retryPolicy : RetryPolicy.none();
    }

    public static final String RUN_ID_KEY = "__run_id__";
    public static final String WORKFLOW_DEFINITION_ID_KEY = "__workflow_definition_id__";
    public static final String TENANT_ID_KEY = "__tenant_id__";
    public static final String NODE_ID_KEY = "__node_id__";
    public static final String NODE_NAME_KEY = "__node_name__";
    public static final String NODE_TYPE_KEY = "__node_type__";
    public static final String ATTEMPT_KEY = "__attempt__";
    public static final String NODE_CONFIGURATION_KEY = "__node_configuration__";
    public static final String WORKFLOW_VARIABLES_KEY = "__workflow_variables__";
    public static final String TIMEOUT_SECONDS_KEY = "__timeout_seconds__";
    public static final String COLLABORATION_CONTEXT_KEY = "__collaboration__";
    public static final String EXECUTOR_SELECTION_KEY = ExecutorSelectionPolicy.CONTEXT_KEY;
    public static final String EXECUTOR_SELECTION_STRATEGY_KEY = ExecutorSelectionPolicy.STRATEGY_KEY;
    public static final String LEGACY_CONTEXT_KEY = "context";

    public Map<String, Object> workflowVariables() {
        return mapValue(WORKFLOW_VARIABLES_KEY);
    }

    public Map<String, Object> nodeConfiguration() {
        return mapValue(NODE_CONFIGURATION_KEY);
    }

    public Optional<CollaborationContext> collaborationContext() {
        return CollaborationContext.fromContextValue(context.get(COLLABORATION_CONTEXT_KEY));
    }

    public ExecutorSelectionPolicy executorSelectionPolicy() {
        return ExecutorSelectionPolicy.fromContext(context);
    }

    public String taskId() {
        return taskId(runId, nodeId, attempt);
    }

    public String idempotencyKey() {
        return taskId();
    }

    public static String taskId(WorkflowRunId runId, NodeId nodeId, int attempt) {
        Objects.requireNonNull(runId, "WorkflowRunId cannot be null");
        Objects.requireNonNull(nodeId, "NodeId cannot be null");
        validateAttempt(attempt);
        return runId.value()
                + ":"
                + nodeId.value()
                + ":"
                + attempt;
    }

    private static void validateAttempt(int attempt) {
        if (attempt <= 0) {
            throw new GamelanException(ErrorCode.VALIDATION_FAILED, "NodeExecutionTask attempt must be positive");
        }
    }

    private static void validateTokenIdentity(
            WorkflowRunId runId,
            NodeId nodeId,
            int attempt,
            ExecutionToken token) {

        if (!runId.equals(token.runId())
                || !nodeId.equals(token.nodeId())
                || attempt != token.attempt()) {
            throw new GamelanException(
                    ErrorCode.VALIDATION_FAILED,
                    "ExecutionToken must match task identity");
        }
    }

    private static void validateTokenTenant(Map<String, Object> context, ExecutionToken token) {
        if (token.tenantId() == null || context == null) {
            return;
        }
        Object tenantValue = context.get(TENANT_ID_KEY);
        if (!(tenantValue instanceof String tenantId) || tenantId.isBlank()) {
            return;
        }
        if (!token.tenantId().value().equals(tenantId)) {
            throw new GamelanException(
                    ErrorCode.VALIDATION_FAILED,
                    "ExecutionToken tenant must match task context tenant");
        }
    }

    private Map<String, Object> mapValue(String key) {
        if (context == null) {
            return Map.of();
        }

        Object value = context.get(key);
        if (!(value instanceof Map<?, ?> rawMap)) {
            return Map.of();
        }
        return ExecutionPayloads.immutableStringKeyMap(rawMap);
    }
}
