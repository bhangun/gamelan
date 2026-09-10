package tech.kayys.gamelan.engine.event;

import java.time.Instant;

import tech.kayys.gamelan.engine.error.ErrorInfo;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;

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