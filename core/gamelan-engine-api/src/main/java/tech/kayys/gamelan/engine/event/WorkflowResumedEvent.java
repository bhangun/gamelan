package tech.kayys.gamelan.engine.event;

import java.time.Instant;
import java.util.Map;

import tech.kayys.gamelan.engine.workflow.WorkflowRunId;

public record WorkflowResumedEvent(
        String eventId,
        WorkflowRunId runId,
        Map<String, Object> resumeData,
        Instant occurredAt) implements ExecutionEvent {
    @Override
    public String eventType() {
        return "WorkflowResumed";
    }
}
