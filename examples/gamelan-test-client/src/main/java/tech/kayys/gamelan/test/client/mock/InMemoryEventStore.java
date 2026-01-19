package tech.kayys.gamelan.test.client.mock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.engine.event.EventStore;
import tech.kayys.gamelan.engine.event.ExecutionEvent;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;

@ApplicationScoped
@Alternative
@Priority(1)
public class InMemoryEventStore implements EventStore {

    private final Map<WorkflowRunId, List<ExecutionEvent>> store = new ConcurrentHashMap<>();

    @Override
    public Uni<Void> appendEvents(
            WorkflowRunId runId,
            List<ExecutionEvent> events,
            long expectedVersion) {
        store.computeIfAbsent(runId, k -> new ArrayList<>()).addAll(events);
        return Uni.createFrom().voidItem();
    }

    @Override
    public Uni<List<ExecutionEvent>> getEvents(WorkflowRunId runId) {
        return Uni.createFrom().item(store.getOrDefault(runId, Collections.emptyList()));
    }

    @Override
    public Uni<List<ExecutionEvent>> getEventsAfterVersion(
            WorkflowRunId runId,
            long afterVersion) {
        return Uni.createFrom().item(Collections.emptyList());
    }

    @Override
    public Uni<List<ExecutionEvent>> getEventsByType(
            WorkflowRunId runId,
            String eventType) {
        return Uni.createFrom().item(Collections.emptyList());
    }
}
