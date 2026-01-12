package tech.kayys.silat.model.event;

import java.time.Instant;
import java.util.List;

import tech.kayys.silat.model.NodeId;
import tech.kayys.silat.model.TenantId;
import tech.kayys.silat.model.WorkflowRunId;

public record CompensationStartedEvent(
        String eventId,
        WorkflowRunId runId,
        TenantId tenantId,
        List<NodeId> nodesToCompensate,
        Instant occurredAt) implements ExecutionEvent {
    
    @Override
    public String eventType() {
        return "CompensationStarted";
    }
}