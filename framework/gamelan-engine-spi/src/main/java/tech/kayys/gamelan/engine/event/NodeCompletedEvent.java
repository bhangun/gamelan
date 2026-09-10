package tech.kayys.gamelan.engine.event;

import java.time.Instant;
import java.util.Map;

import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.payload.ExecutionPayloads;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;

public record NodeCompletedEvent(
        String eventId,
        WorkflowRunId runId,
        NodeId nodeId,
        int attempt,
        Map<String, Object> output,
        Instant occurredAt) implements ExecutionEvent {
    public NodeCompletedEvent {
        output = ExecutionPayloads.immutableMap(output);
    }

    @Override
    public String eventType() {
        return "NodeCompleted";
    }
}
