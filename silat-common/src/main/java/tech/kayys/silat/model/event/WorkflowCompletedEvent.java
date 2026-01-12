package tech.kayys.silat.model.event;

import java.time.Instant;
import java.util.Map;

import tech.kayys.silat.model.WorkflowRunId;

public record WorkflowCompletedEvent(
        String eventId,
        WorkflowRunId runId,
        Map<String, Object> outputs,
        Instant occurredAt) implements ExecutionEvent {
    @Override
    public String eventType() {
        return "WorkflowCompleted";
    }
}
