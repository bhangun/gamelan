package tech.kayys.gamelan.engine.event;

import java.time.Instant;

import tech.kayys.gamelan.engine.error.ErrorInfo;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;

/**
 * Execution Event - Event sourcing events
 */
public sealed interface ExecutionEvent permits
        WorkflowStartedEvent,
        NodeScheduledEvent,
        NodeStartedEvent,
        NodeCompletedEvent,
        NodeFailedEvent,
        WorkflowSuspendedEvent,
        WorkflowResumedEvent,
        WorkflowCompletedEvent,
        WorkflowFailedEvent,
        WorkflowCancelledEvent,
        CompensationStartedEvent,
        CompensationCompletedEvent,
        CompensationFailedEvent,
        GenericExecutionEvent {

    String eventId();

    WorkflowRunId runId();

    Instant occurredAt();

    String eventType();

    static ExecutionEvent nodeDeadLettered(WorkflowRunId runId, NodeId nodeId, String reason) {
        return new NodeFailedEvent(
                java.util.UUID.randomUUID().toString(),
                runId,
                nodeId,
                1,
                new ErrorInfo("DEAD_LETTER", reason, null, null),
                false,
                Instant.now());
    }
}
