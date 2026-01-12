package tech.kayys.silat.execution;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import tech.kayys.silat.model.ErrorInfo;
import tech.kayys.silat.model.ExecutionToken;
import tech.kayys.silat.model.NodeId;
import tech.kayys.silat.model.WaitInfo;
import tech.kayys.silat.model.WorkflowRunId;

/**
 * 🔒 Structured execution result.
 * Error as data, not exceptions.
 */
public interface NodeExecutionResult {

    // Record-style accessors (to match DefaultNodeExecutionResult record)
    default WorkflowRunId runId() {
        return null;
    }

    default NodeId nodeId() {
        return null;
    }

    default int attempt() {
        return 0;
    }

    default NodeExecutionStatus status() {
        return getStatus();
    }

    default Map<String, Object> output() {
        return null;
    }

    default ErrorInfo error() {
        return null;
    }

    default ExecutionToken executionToken() {
        return null;
    }

    // Legacy interface methods
    NodeExecutionStatus getStatus();

    String getNodeId();

    Instant getExecutedAt();

    Duration getDuration();

    ExecutionContext getUpdatedContext();

    ExecutionError getError();

    WaitInfo getWaitInfo();

    Map<String, Object> getMetadata();
}
