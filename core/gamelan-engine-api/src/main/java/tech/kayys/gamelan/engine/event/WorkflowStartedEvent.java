package tech.kayys.gamelan.engine.event;

import java.time.Instant;
import java.util.Map;

import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinitionId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;

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