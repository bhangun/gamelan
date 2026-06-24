package tech.kayys.gamelan.engine.node;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

import tech.kayys.gamelan.engine.error.ErrorCode;
import tech.kayys.gamelan.engine.error.ErrorInfo;
import tech.kayys.gamelan.engine.error.GamelanException;
import tech.kayys.gamelan.engine.execution.ExecutionToken;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;

/**
 * Shared validation for node execution results produced by local and remote executors.
 */
public final class NodeExecutionResults {

    private NodeExecutionResults() {
    }

    public enum Acceptance {
        ACCEPT,
        ALREADY_PROCESSED,
        STALE,
        ALREADY_APPLIED,
        RUN_NOT_ACCEPTING_RESULTS
    }

    public static void validateIdentity(
            WorkflowRunId runId,
            NodeId nodeId,
            int attempt,
            NodeExecutionStatus status,
            ExecutionToken token) {

        Objects.requireNonNull(runId, "WorkflowRunId cannot be null");
        Objects.requireNonNull(nodeId, "NodeId cannot be null");
        Objects.requireNonNull(status, "NodeExecutionStatus cannot be null");
        if (attempt <= 0) {
            throw new GamelanException(
                    ErrorCode.VALIDATION_FAILED,
                    "NodeExecutionResult attempt must be positive");
        }
        if (token != null && (!runId.equals(token.runId())
                || !nodeId.equals(token.nodeId())
                || attempt != token.attempt())) {
            throw new GamelanException(
                    ErrorCode.VALIDATION_FAILED,
                    "ExecutionToken must match result identity");
        }
    }

    public static void validateResultForRun(
            WorkflowRunId expectedRunId,
            NodeExecutionResult result) {

        if (expectedRunId == null) {
            throw new GamelanException(
                    ErrorCode.TASK_VALIDATION_FAILED,
                    "WorkflowRunId is required for node result handling");
        }
        if (result == null) {
            throw new GamelanException(
                    ErrorCode.TASK_VALIDATION_FAILED,
                    "NodeExecutionResult is required");
        }

        WorkflowRunId actualRunId = result.runId();
        if (actualRunId == null) {
            throw new GamelanException(
                    ErrorCode.TASK_VALIDATION_FAILED,
                    "NodeExecutionResult runId is required");
        }
        if (!expectedRunId.equals(actualRunId)) {
            throw new GamelanException(
                    ErrorCode.TASK_VALIDATION_FAILED,
                    "NodeExecutionResult runId mismatch: expected "
                            + expectedRunId.value()
                            + " but got "
                            + actualRunId.value());
        }
        if (result.nodeId() == null) {
            throw new GamelanException(
                    ErrorCode.TASK_VALIDATION_FAILED,
                    "NodeExecutionResult nodeId is required");
        }
        if (result.status() == null) {
            throw new GamelanException(
                    ErrorCode.TASK_VALIDATION_FAILED,
                    "NodeExecutionResult status is required");
        }

        validateIdentity(
                actualRunId,
                result.nodeId(),
                result.attempt(),
                result.status(),
                result.executionToken());
        validateResultSemantics(
                result.status(),
                result.error(),
                "NodeExecutionResult",
                ErrorCode.TASK_VALIDATION_FAILED);
    }

    public static void validateResultSemantics(
            NodeExecutionStatus status,
            ErrorInfo error,
            String subject) {

        validateResultSemantics(status, error, subject, ErrorCode.VALIDATION_FAILED);
    }

    public static NodeExecutionResult fromExternal(
            String runId,
            String nodeId,
            int attempt,
            String status,
            Map<String, Object> output,
            ErrorInfo error,
            String executionToken,
            Instant tokenExpiresAt) {

        return fromExternal(runId, nodeId, attempt, status, output, error, executionToken, (TenantId) null,
                tokenExpiresAt);
    }

    public static NodeExecutionResult fromExternal(
            String runId,
            String nodeId,
            int attempt,
            String status,
            Map<String, Object> output,
            ErrorInfo error,
            String executionToken,
            String tenantId,
            Instant tokenExpiresAt) {

        TenantId domainTenantId = tenantId == null || tenantId.isBlank() ? null : TenantId.of(tenantId);
        return fromExternal(runId, nodeId, attempt, status, output, error, executionToken, domainTenantId,
                tokenExpiresAt);
    }

    public static NodeExecutionResult fromExternal(
            String runId,
            String nodeId,
            int attempt,
            String status,
            Map<String, Object> output,
            ErrorInfo error,
            String executionToken,
            TenantId tenantId,
            Instant tokenExpiresAt) {

        validateExternalAttempt(attempt);

        WorkflowRunId domainRunId = WorkflowRunId.of(runId);
        NodeId domainNodeId = NodeId.of(nodeId);
        NodeExecutionStatus domainStatus = statusFromExternal(status);
        validateExternalSemantics(domainStatus, error);

        return new DefaultNodeExecutionResult(
                domainRunId,
                domainNodeId,
                attempt,
                domainStatus,
                output,
                error,
                tokenFromExternal(executionToken, domainRunId, tenantId, domainNodeId, attempt, tokenExpiresAt));
    }

    public static NodeExecutionStatus statusFromExternal(String status) {
        if (status == null || status.isBlank()) {
            throw new GamelanException(ErrorCode.VALIDATION_FAILED, "TaskResult status must be specified");
        }

        String normalized = status.trim();
        if (normalized.startsWith("TASK_STATUS_")) {
            normalized = normalized.substring("TASK_STATUS_".length());
        }
        if (normalized.equals("UNSPECIFIED") || normalized.equals("UNRECOGNIZED")) {
            throw new GamelanException(ErrorCode.VALIDATION_FAILED, "TaskResult status must be specified");
        }

        try {
            return NodeExecutionStatus.valueOf(normalized);
        } catch (IllegalArgumentException e) {
            throw new GamelanException(
                    ErrorCode.VALIDATION_FAILED,
                    "Unsupported TaskResult status: " + status);
        }
    }

    public static void validateExternalAttempt(int attempt) {
        if (attempt <= 0) {
            throw new GamelanException(ErrorCode.VALIDATION_FAILED, "TaskResult attempt must be positive");
        }
    }

    public static Acceptance acceptanceFor(
            NodeExecution execution,
            NodeExecutionResult result,
            boolean alreadyProcessed) {

        Objects.requireNonNull(result, "NodeExecutionResult cannot be null");
        if (alreadyProcessed) {
            return Acceptance.ALREADY_PROCESSED;
        }
        if (execution == null) {
            throw new GamelanException(
                    ErrorCode.TASK_NOT_FOUND,
                    "Node execution not found: " + result.nodeId().value());
        }

        int currentAttempt = execution.getAttempt();
        int resultAttempt = result.attempt();
        if (currentAttempt > resultAttempt) {
            return Acceptance.STALE;
        }
        if (currentAttempt < resultAttempt) {
            throw new GamelanException(
                    ErrorCode.TASK_VALIDATION_FAILED,
                    "Future node result attempt: expected " + currentAttempt + " but got " + resultAttempt);
        }
        if (execution.getStatus().isTerminal()) {
            if (execution.getStatus() != result.status()) {
                throw new GamelanException(
                        ErrorCode.TASK_VALIDATION_FAILED,
                        "Conflicting terminal node result: existing "
                                + execution.getStatus()
                                + " but got "
                                + result.status());
            }
            return Acceptance.ALREADY_APPLIED;
        }
        return Acceptance.ACCEPT;
    }

    private static void validateExternalSemantics(NodeExecutionStatus status, ErrorInfo error) {
        validateResultSemantics(status, error, "TaskResult", ErrorCode.VALIDATION_FAILED);
    }

    private static void validateResultSemantics(
            NodeExecutionStatus status,
            ErrorInfo error,
            String subject,
            ErrorCode errorCode) {

        String label = subject != null && !subject.isBlank() ? subject : "NodeExecutionResult";
        if (status != NodeExecutionStatus.COMPLETED && status != NodeExecutionStatus.FAILED) {
            throw new GamelanException(
                    errorCode,
                    label + " status must be COMPLETED or FAILED: " + status);
        }
        if (status == NodeExecutionStatus.COMPLETED && error != null) {
            throw new GamelanException(
                    errorCode,
                    "Completed " + label + " cannot include error");
        }
        if (status == NodeExecutionStatus.FAILED && error == null) {
            throw new GamelanException(
                    errorCode,
                    "Failed " + label + " must include error");
        }
    }

    private static ExecutionToken tokenFromExternal(
            String executionToken,
            WorkflowRunId runId,
            TenantId tenantId,
            NodeId nodeId,
            int attempt,
            Instant expiresAt) {

        if (executionToken == null || executionToken.isBlank()) {
            return null;
        }
        return new ExecutionToken(
                executionToken,
                runId,
                tenantId,
                nodeId,
                attempt,
                expiresAt != null ? expiresAt : Instant.now().plusSeconds(3600));
    }
}
