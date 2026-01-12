package tech.kayys.silat.model.event;

import java.time.Instant;
import java.util.List;

import tech.kayys.silat.model.NodeId;
import tech.kayys.silat.model.TenantId;
import tech.kayys.silat.model.WorkflowRunId;

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