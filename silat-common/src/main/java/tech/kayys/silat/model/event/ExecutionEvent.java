package tech.kayys.silat.model.event;

import java.time.Instant;

import tech.kayys.silat.model.WorkflowRunId;

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

    static ExecutionEvent nodeDeadLettered(WorkflowRunId runId, tech.kayys.silat.model.NodeId nodeId, String reason) {
        return new NodeFailedEvent(
                java.util.UUID.randomUUID().toString(),
                runId,
                nodeId,
                1,
                new tech.kayys.silat.model.ErrorInfo("DEAD_LETTER", reason, null, null),
                false,
                Instant.now());
    }
}
