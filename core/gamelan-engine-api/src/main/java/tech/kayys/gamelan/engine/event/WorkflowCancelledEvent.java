package tech.kayys.gamelan.engine.event;

import java.time.Instant;

import tech.kayys.gamelan.engine.workflow.WorkflowRunId;

public record WorkflowCancelledEvent(
        String eventId,
        WorkflowRunId runId,
        String reason,
        Instant occurredAt) implements ExecutionEvent {
    @Override
    public String eventType() {
        return "WorkflowCancelled";
    }
}
