package tech.kayys.gamelan.engine.execution;

import java.util.Map;

import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;

public interface ExecutionHistoryRepository {

    Uni<Void> append(WorkflowRunId runId, String type, String message, Map<String, Object> metadata);

    default Uni<Void> append(
            WorkflowRunId runId,
            TenantId tenantId,
            String type,
            String message,
            Map<String, Object> metadata) {
        return append(runId, type, message, metadata);
    }

    Uni<Void> appendEvents(WorkflowRunId runId, java.util.List<tech.kayys.gamelan.engine.event.ExecutionEvent> events);

    default Uni<Void> appendEvents(
            WorkflowRunId runId,
            TenantId tenantId,
            java.util.List<tech.kayys.gamelan.engine.event.ExecutionEvent> events) {
        return appendEvents(runId, events);
    }

    Uni<ExecutionHistory> load(WorkflowRunId runId);

    default Uni<ExecutionHistory> load(WorkflowRunId runId, TenantId tenantId) {
        return load(runId);
    }

    Uni<Boolean> isNodeResultProcessed(WorkflowRunId runId, NodeId nodeId, int attempt);

    default Uni<Boolean> isNodeResultProcessed(WorkflowRunId runId, TenantId tenantId, NodeId nodeId, int attempt) {
        return isNodeResultProcessed(runId, nodeId, attempt);
    }

    Uni<Boolean> markNodeResultProcessed(WorkflowRunId runId, NodeId nodeId, int attempt);

    default Uni<Boolean> markNodeResultProcessed(WorkflowRunId runId, TenantId tenantId, NodeId nodeId, int attempt) {
        return markNodeResultProcessed(runId, nodeId, attempt);
    }

    default Uni<Boolean> isExternalSignalProcessed(WorkflowRunId runId, String idempotencyKey) {
        return Uni.createFrom().item(false);
    }

    default Uni<Boolean> isExternalSignalProcessed(WorkflowRunId runId, TenantId tenantId, String idempotencyKey) {
        return isExternalSignalProcessed(runId, idempotencyKey);
    }

    default Uni<Boolean> markExternalSignalProcessed(WorkflowRunId runId, String idempotencyKey) {
        return Uni.createFrom().item(true);
    }

    default Uni<Boolean> markExternalSignalProcessed(WorkflowRunId runId, TenantId tenantId, String idempotencyKey) {
        return markExternalSignalProcessed(runId, idempotencyKey);
    }

    /**
     * Append the accepted-signal audit row if it has not already been appended
     * for the given idempotency key.
     *
     * <p>
     * Implementations with durable uniqueness should override this method. The
     * default preserves compatibility for local/custom repositories by appending a
     * normal history row and reporting it as newly inserted.
     */
    default Uni<Boolean> appendSignalReceivedAudit(
            WorkflowRunId runId,
            TenantId tenantId,
            String idempotencyKey,
            String signalName,
            Map<String, Object> metadata) {
        return append(
                runId,
                tenantId,
                "SIGNAL_RECEIVED",
                signalName,
                metadata)
                .replaceWith(true);
    }

    /**
     * Append the ignored-signal audit row if it has not already been appended for
     * the given idempotency key.
     */
    default Uni<Boolean> appendSignalIgnoredAudit(
            WorkflowRunId runId,
            TenantId tenantId,
            String idempotencyKey,
            String reason,
            Map<String, Object> metadata) {
        return append(
                runId,
                tenantId,
                "SIGNAL_IGNORED",
                reason,
                metadata)
                .replaceWith(true);
    }

    default Uni<Boolean> isCompensationNodeProcessed(WorkflowRunId runId, NodeId nodeId) {
        return Uni.createFrom().item(false);
    }

    default Uni<Boolean> isCompensationNodeProcessed(WorkflowRunId runId, TenantId tenantId, NodeId nodeId) {
        return isCompensationNodeProcessed(runId, nodeId);
    }

    default Uni<Boolean> markCompensationNodeProcessed(WorkflowRunId runId, NodeId nodeId) {
        return Uni.createFrom().item(true);
    }

    default Uni<Boolean> markCompensationNodeProcessed(WorkflowRunId runId, TenantId tenantId, NodeId nodeId) {
        return markCompensationNodeProcessed(runId, nodeId);
    }
}
