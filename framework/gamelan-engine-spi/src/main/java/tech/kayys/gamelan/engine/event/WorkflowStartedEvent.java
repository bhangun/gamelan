package tech.kayys.gamelan.engine.event;

import java.time.Instant;
import java.util.Map;

import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.payload.ExecutionPayloads;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinitionId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;

public record WorkflowStartedEvent(
        String eventId,
        WorkflowRunId runId,
        WorkflowDefinitionId definitionId,
        TenantId tenantId,
        String workflowVersion,
        Map<String, Object> inputs,
        Instant occurredAt) implements ExecutionEvent {

    public WorkflowStartedEvent(
            String eventId,
            WorkflowRunId runId,
            WorkflowDefinitionId definitionId,
            TenantId tenantId,
            Map<String, Object> inputs,
            Instant occurredAt) {
        this(eventId, runId, definitionId, tenantId, "unknown", inputs, occurredAt);
    }

    public WorkflowStartedEvent {
        workflowVersion = workflowVersion == null || workflowVersion.isBlank() ? "unknown" : workflowVersion;
        inputs = ExecutionPayloads.immutableMap(inputs);
    }

    @Override
    public String eventType() {
        return "WorkflowStarted";
    }
}
