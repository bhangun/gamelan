package tech.kayys.silat.model;

import java.util.List;

import io.smallrye.mutiny.Uni;
import tech.kayys.silat.model.event.ExecutionEvent;

public interface EventStore {

    /**
     * Append events to the event stream
     * 
     * @param runId           Workflow run identifier
     * @param events          Events to append
     * @param expectedVersion Expected version for optimistic locking
     * @return Uni<Void>
     */
    Uni<Void> appendEvents(
            WorkflowRunId runId,
            List<ExecutionEvent> events,
            long expectedVersion);

    /**
     * Get all events for a workflow run
     */
    Uni<List<ExecutionEvent>> getEvents(WorkflowRunId runId);

    /**
     * Get events after a specific version
     */
    Uni<List<ExecutionEvent>> getEventsAfterVersion(
            WorkflowRunId runId,
            long afterVersion);

    /**
     * Get events by type
     */
    Uni<List<ExecutionEvent>> getEventsByType(
            WorkflowRunId runId,
            String eventType);
}
