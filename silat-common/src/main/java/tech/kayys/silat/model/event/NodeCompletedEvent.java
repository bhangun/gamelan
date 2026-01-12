package tech.kayys.silat.model.event;

import java.time.Instant;
import java.util.Map;

import tech.kayys.silat.model.NodeId;
import tech.kayys.silat.model.WorkflowRunId;

public record NodeCompletedEvent(
        String eventId,
        WorkflowRunId runId,
        NodeId nodeId,
        int attempt,
        Map<String, Object> output,
        Instant occurredAt) implements ExecutionEvent {
    @Override
    public String eventType() {
        return "NodeCompleted";
    }
}
