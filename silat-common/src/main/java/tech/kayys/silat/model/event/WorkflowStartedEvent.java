package tech.kayys.silat.model.event;

import java.time.Instant;
import java.util.Map;

import tech.kayys.silat.model.TenantId;
import tech.kayys.silat.model.WorkflowDefinitionId;
import tech.kayys.silat.model.WorkflowRunId;

public record WorkflowStartedEvent(
        String eventId,
        WorkflowRunId runId,
        WorkflowDefinitionId definitionId,
        TenantId tenantId,
        Map<String, Object> inputs,
        Instant occurredAt) implements ExecutionEvent {
    @Override
    public String eventType() {
        return "WorkflowStarted";
    }
}