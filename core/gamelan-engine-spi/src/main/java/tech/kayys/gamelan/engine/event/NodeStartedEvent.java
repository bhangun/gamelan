package tech.kayys.gamelan.engine.event;

import java.time.Instant;

import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;

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