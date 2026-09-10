package tech.kayys.gamelan.engine.event;

import java.time.Instant;

import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;

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
