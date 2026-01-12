package tech.kayys.silat.model.event;

import java.time.Instant;

import tech.kayys.silat.model.ErrorInfo;
import tech.kayys.silat.model.NodeId;
import tech.kayys.silat.model.WorkflowRunId;

public record NodeFailedEvent(
        String eventId,
        WorkflowRunId runId,
        NodeId nodeId,
        int attempt,
        ErrorInfo error,
        boolean willRetry,
        Instant occurredAt) implements ExecutionEvent {
    @Override
    public String eventType() {
        return "NodeFailed";
    }
}
