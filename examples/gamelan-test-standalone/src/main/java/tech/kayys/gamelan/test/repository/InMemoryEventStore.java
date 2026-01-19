package tech.kayys.gamelan.test.repository;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.annotation.Priority;
import tech.kayys.gamelan.model.EventStore;
import tech.kayys.gamelan.model.WorkflowRunId;
import tech.kayys.gamelan.model.event.ExecutionEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Alternative
@Priority(1)
@ApplicationScoped
public class InMemoryEventStore implements EventStore {

    private final Map<WorkflowRunId, List<ExecutionEvent>> streams = new ConcurrentHashMap<>();

    @Override
    public Uni<Void> appendEvents(WorkflowRunId runId, List<ExecutionEvent> events, long expectedVersion) {
        streams.computeIfAbsent(runId, k -> new ArrayList<>()).addAll(events);
        return Uni.createFrom().voidItem();
    }

    @Override
    public Uni<List<ExecutionEvent>> getEvents(WorkflowRunId runId) {
        return Uni.createFrom().item(streams.getOrDefault(runId, List.of()));
    }

    @Override
    public Uni<List<ExecutionEvent>> getEventsAfterVersion(WorkflowRunId runId, long afterVersion) {
        List<ExecutionEvent> events = streams.getOrDefault(runId, List.of());
        if (afterVersion >= events.size()) return Uni.createFrom().item(List.of());
        return Uni.createFrom().item(events.subList((int)afterVersion, events.size()));
    }

    @Override
    public Uni<List<ExecutionEvent>> getEventsByType(WorkflowRunId runId, String eventType) {
        return Uni.createFrom().item(streams.getOrDefault(runId, List.of()).stream()
                .filter(e -> e.eventType().equals(eventType))
                .toList());
    }
}
