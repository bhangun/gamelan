package tech.kayys.gamelan.engine.event;

import java.time.Instant;
import java.util.List;

import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;

public record CompensationCompletedEvent(
        String eventId,
        WorkflowRunId runId,
        TenantId tenantId,
        List<NodeId> compensatedNodes,
        Instant occurredAt) implements ExecutionEvent {

    @Override
    public String eventType() {
        return "CompensationCompleted";
    }
}