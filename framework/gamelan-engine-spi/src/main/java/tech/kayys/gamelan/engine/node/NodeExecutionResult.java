package tech.kayys.gamelan.engine.node;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import tech.kayys.gamelan.engine.error.ErrorInfo;
import tech.kayys.gamelan.engine.execution.ExecutionContext;
import tech.kayys.gamelan.engine.execution.ExecutionError;
import tech.kayys.gamelan.engine.execution.ExecutionToken;
import tech.kayys.gamelan.engine.run.WaitInfo;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;

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
