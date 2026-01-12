package tech.kayys.silat.model.event;

import java.time.Instant;

import tech.kayys.silat.model.ErrorInfo;
import tech.kayys.silat.model.WorkflowRunId;

public record WorkflowFailedEvent(
        String eventId,
        WorkflowRunId runId,
        ErrorInfo error,
        Instant occurredAt) implements ExecutionEvent {
    @Override
    public String eventType() {
        return "WorkflowFailed";
    }
}