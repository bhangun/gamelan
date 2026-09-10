package tech.kayys.gamelan.engine.node;

import java.util.Objects;

import tech.kayys.gamelan.engine.error.ErrorCode;
import tech.kayys.gamelan.engine.error.GamelanException;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;

/**
 * Outcome of applying or deduplicating a node result.
 */
public record NodeResultHandlingOutcome(
        WorkflowRunId runId,
        TenantId tenantId,
        NodeId nodeId,
        int attempt,
        NodeExecutionResults.Acceptance acceptance,
        boolean runUpdated,
        boolean historyAppended,
        boolean processedMarkerWritten,
        boolean retryWakeupScheduled) {

    public NodeResultHandlingOutcome {
        Objects.requireNonNull(runId, "WorkflowRunId cannot be null");
        Objects.requireNonNull(nodeId, "NodeId cannot be null");
        Objects.requireNonNull(acceptance, "Node result acceptance cannot be null");
        if (attempt <= 0) {
            throw new GamelanException(ErrorCode.VALIDATION_FAILED, "Node result attempt must be positive");
        }
    }

    public boolean accepted() {
        return acceptance == NodeExecutionResults.Acceptance.ACCEPT;
    }

    public boolean duplicate() {
        return acceptance == NodeExecutionResults.Acceptance.ALREADY_PROCESSED
                || acceptance == NodeExecutionResults.Acceptance.ALREADY_APPLIED;
    }

    public boolean ignored() {
        return acceptance == NodeExecutionResults.Acceptance.STALE
                || acceptance == NodeExecutionResults.Acceptance.RUN_NOT_ACCEPTING_RESULTS;
    }
}
