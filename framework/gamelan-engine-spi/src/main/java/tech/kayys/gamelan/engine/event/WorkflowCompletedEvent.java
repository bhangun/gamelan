package tech.kayys.gamelan.engine.event;

import java.time.Instant;
import java.util.Map;

import tech.kayys.gamelan.engine.payload.ExecutionPayloads;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;

public record WorkflowCompletedEvent(
        String eventId,
        WorkflowRunId runId,
        Map<String, Object> outputs,
        Instant occurredAt) implements ExecutionEvent {
    public WorkflowCompletedEvent {
        outputs = ExecutionPayloads.immutableMap(outputs);
    }

    @Override
    public String eventType() {
        return "WorkflowCompleted";
    }
}
