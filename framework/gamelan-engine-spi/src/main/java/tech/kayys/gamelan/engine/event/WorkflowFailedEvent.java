package tech.kayys.gamelan.engine.event;

import java.time.Instant;

import tech.kayys.gamelan.engine.error.ErrorInfo;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;

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