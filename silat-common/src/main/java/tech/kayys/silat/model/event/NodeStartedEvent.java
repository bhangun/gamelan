package tech.kayys.silat.model.event;

import java.time.Instant;

import tech.kayys.silat.model.NodeId;
import tech.kayys.silat.model.WorkflowRunId;

public record NodeStartedEvent(
        String eventId,
        WorkflowRunId runId,
        NodeId nodeId,
        int attempt,
        Instant occurredAt) implements ExecutionEvent {
    @Override
    public String eventType() {
        return "NodeStarted";
    }
}