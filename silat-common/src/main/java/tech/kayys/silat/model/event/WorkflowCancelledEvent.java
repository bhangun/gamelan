package tech.kayys.silat.model.event;

import java.time.Instant;

import tech.kayys.silat.model.WorkflowRunId;

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
