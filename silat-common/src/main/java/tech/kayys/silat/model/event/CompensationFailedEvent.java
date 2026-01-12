package tech.kayys.silat.model.event;

import java.time.Instant;

import tech.kayys.silat.model.ErrorInfo;
import tech.kayys.silat.model.TenantId;
import tech.kayys.silat.model.WorkflowRunId;

public record CompensationFailedEvent(
        String eventId,
        WorkflowRunId runId,
        TenantId tenantId,
        ErrorInfo error,
        Instant occurredAt) implements ExecutionEvent {
    
    @Override
    public String eventType() {
        return "CompensationFailed";
    }
}