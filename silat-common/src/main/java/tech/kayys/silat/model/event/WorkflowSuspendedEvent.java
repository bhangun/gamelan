package tech.kayys.silat.model.event;

import java.time.Instant;

import tech.kayys.silat.model.NodeId;
import tech.kayys.silat.model.WorkflowRunId;

public record WorkflowSuspendedEvent(
        String eventId,
        WorkflowRunId runId,
        String reason,
        NodeId waitingOnNodeId,
        Instant occurredAt) implements ExecutionEvent {
    @Override
    public String eventType() {
        return "WorkflowSuspended";
    }
}
