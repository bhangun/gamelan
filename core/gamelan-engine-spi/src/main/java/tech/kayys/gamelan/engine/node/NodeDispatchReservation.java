package tech.kayys.gamelan.engine.node;

import java.util.Objects;

import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;

/**
 * Durable reservation outcome for dispatching one node attempt.
 */
public record NodeDispatchReservation(
        WorkflowRunId runId,
        TenantId tenantId,
        NodeId nodeId,
        int attempt,
        boolean reserved,
        String reason) {

    public NodeDispatchReservation {
        runId = Objects.requireNonNull(runId, "WorkflowRunId cannot be null");
        nodeId = Objects.requireNonNull(nodeId, "NodeId cannot be null");
        if (reserved && attempt <= 0) {
            throw new IllegalArgumentException("Reserved node dispatch attempt must be positive");
        }
        if (!reserved && attempt < 0) {
            throw new IllegalArgumentException("Skipped node dispatch attempt cannot be negative");
        }
        reason = reason != null && !reason.isBlank() ? reason : (reserved ? "reserved" : "skipped");
    }

    public static NodeDispatchReservation reserved(
            WorkflowRunId runId,
            TenantId tenantId,
            NodeId nodeId,
            int attempt) {
        return new NodeDispatchReservation(runId, tenantId, nodeId, attempt, true, "reserved");
    }

    public static NodeDispatchReservation skipped(
            WorkflowRunId runId,
            TenantId tenantId,
            NodeId nodeId,
            String reason) {
        return new NodeDispatchReservation(runId, tenantId, nodeId, 0, false, reason);
    }
}
