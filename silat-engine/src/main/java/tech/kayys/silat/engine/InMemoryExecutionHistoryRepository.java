package tech.kayys.silat.engine;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import tech.kayys.silat.execution.ExecutionHistory;
import tech.kayys.silat.model.NodeId;
import tech.kayys.silat.model.WorkflowRunId;
import tech.kayys.silat.model.event.ExecutionEvent;
import tech.kayys.silat.model.event.GenericExecutionEvent;

@ApplicationScoped
public class InMemoryExecutionHistoryRepository implements ExecutionHistoryRepository {

    private final Map<WorkflowRunId, List<ExecutionEvent>> events = new ConcurrentHashMap<>();
    private final Set<String> processedNodeKeys = ConcurrentHashMap.newKeySet();

    @Override
    public Uni<Void> append(
            WorkflowRunId runId,
            String type,
            String message,
            Map<String, Object> metadata) {
        events.computeIfAbsent(runId, k -> new ArrayList<>())
                .add(new GenericExecutionEvent(runId, type, message, Instant.now(), metadata));
        return Uni.createFrom().voidItem();
    }

    @Override
    public Uni<ExecutionHistory> load(WorkflowRunId runId) {
        List<ExecutionEvent> runEvents = events.getOrDefault(runId, List.of());
        return Uni.createFrom().item(
                ExecutionHistory.fromEvents(runId, runEvents));
    }

    @Override
    public Uni<Boolean> isNodeResultProcessed(
            WorkflowRunId runId,
            NodeId nodeId,
            int attempt) {
        boolean processed = !processedNodeKeys.add(runId + ":" + nodeId.value() + ":" + attempt);
        return Uni.createFrom().item(processed);
    }
}