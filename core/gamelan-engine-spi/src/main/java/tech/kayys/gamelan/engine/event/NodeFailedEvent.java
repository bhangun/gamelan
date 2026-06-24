package tech.kayys.gamelan.engine.event;

import java.time.Instant;

import tech.kayys.gamelan.engine.error.ErrorInfo;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;

public record NodeFailedEvent(
        String eventId,
        WorkflowRunId runId,
        NodeId nodeId,
        int attempt,
        ErrorInfo error,
        boolean willRetry,
        Instant occurredAt,
        Instant retryAt) implements ExecutionEvent {

    public NodeFailedEvent(
            String eventId,
            WorkflowRunId runId,
            NodeId nodeId,
            int attempt,
            ErrorInfo error,
            boolean willRetry,
            Instant occurredAt) {
        this(eventId, runId, nodeId, attempt, error, willRetry, occurredAt, null);
    }

    @Override
    public String eventType() {
        return "NodeFailed";
    }
}
