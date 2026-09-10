package tech.kayys.gamelan.engine.event;

import java.util.List;

import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;

public interface EventStore {

    /**
     * Append events to the event stream.
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

    default Uni<Void> appendEvents(
            WorkflowRunId runId,
            TenantId tenantId,
            List<ExecutionEvent> events,
            long expectedVersion) {
        return appendEvents(runId, events, expectedVersion);
    }

    /**
     * Get all events for a workflow run.
     */
    Uni<List<ExecutionEvent>> getEvents(WorkflowRunId runId);

    default Uni<List<ExecutionEvent>> getEvents(WorkflowRunId runId, TenantId tenantId) {
        return getEvents(runId);
    }

    /**
     * Get events after a specific version.
     */
    Uni<List<ExecutionEvent>> getEventsAfterVersion(
            WorkflowRunId runId,
            long afterVersion);

    default Uni<List<ExecutionEvent>> getEventsAfterVersion(
            WorkflowRunId runId,
            TenantId tenantId,
            long afterVersion) {
        return getEventsAfterVersion(runId, afterVersion);
    }

    /**
     * Get events by type.
     */
    Uni<List<ExecutionEvent>> getEventsByType(
            WorkflowRunId runId,
            String eventType);

    default Uni<List<ExecutionEvent>> getEventsByType(
            WorkflowRunId runId,
            TenantId tenantId,
            String eventType) {
        return getEventsByType(runId, eventType);
    }
}
