package tech.kayys.gamelan.sdk.executor;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import tech.kayys.gamelan.engine.execution.ExecutionContext;
import tech.kayys.gamelan.engine.execution.ExecutionError;
import tech.kayys.gamelan.engine.node.NodeExecutionResult;
import tech.kayys.gamelan.engine.node.NodeExecutionStatus;
import tech.kayys.gamelan.engine.error.ErrorInfo;
import tech.kayys.gamelan.engine.node.ExecutionToken;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.node.WaitInfo;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;

/**
 * Simple implementation of NodeExecutionResult for executor SDK
 */
public record SimpleNodeExecutionResult(
        WorkflowRunId runId,
        NodeId nodeId,
        int attempt,
        NodeExecutionStatus status,
        Map<String, Object> output,
        ErrorInfo error,
        ExecutionToken executionToken,
        Instant executedAt,
        Duration duration,
        ExecutionContext updatedContext,
        WaitInfo waitInfo,
        Map<String, Object> metadata) implements NodeExecutionResult {

    @Override
    public NodeExecutionStatus getStatus() {
        return status;
    }

    @Override
    public String getNodeId() {
        return nodeId != null ? nodeId.value() : null;
    }

    @Override
    public Instant getExecutedAt() {
        return executedAt;
    }

    @Override
    public Duration getDuration() {
        return duration;
    }

    @Override
    public ExecutionContext getUpdatedContext() {
        return updatedContext;
    }

    @Override
    public ExecutionError getError() {
        if (error == null) {
            return null;
        }
        return new ExecutionError() {
            @Override
            public String getCode() {
                return error.code();
            }

            @Override
            public Category getCategory() {
                return Category.SYSTEM;
            }

            @Override
            public String getMessage() {
                return error.message();
            }

            @Override
            public boolean isRetriable() {
                return false;
            }

            @Override
            public String getCompensationHint() {
                return null;
            }

            @Override
            public Map<String, Object> getDetails() {
                return error.context();
            }
        };
    }

    @Override
    public WaitInfo getWaitInfo() {
        return waitInfo;
    }

    @Override
    public Map<String, Object> getMetadata() {
        return metadata;
    }

    /**
     * Create a failure result
     */
    public static NodeExecutionResult failure(
            WorkflowRunId runId,
            NodeId nodeId,
            int attempt,
            ErrorInfo error,
            ExecutionToken token) {
        return new SimpleNodeExecutionResult(
                runId,
                nodeId,
                attempt,
                NodeExecutionStatus.FAILED,
                Map.of(),
                error,
                token,
                Instant.now(),
                Duration.ZERO,
                null,
                null,
                Map.of());
    }

    /**
     * Create a success result
     */
    public static NodeExecutionResult success(
            WorkflowRunId runId,
            NodeId nodeId,
            int attempt,
            Map<String, Object> output,
            ExecutionToken token,
            Duration duration) {
        return new SimpleNodeExecutionResult(
                runId,
                nodeId,
                attempt,
                NodeExecutionStatus.COMPLETED,
                output,
                null,
                token,
                Instant.now(),
                duration,
                null,
                null,
                Map.of());
    }
}
