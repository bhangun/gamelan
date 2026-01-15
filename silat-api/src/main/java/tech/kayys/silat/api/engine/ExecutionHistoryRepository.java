package tech.kayys.silat.api.engine;

import java.util.Map;

import io.smallrye.mutiny.Uni;
import tech.kayys.silat.execution.ExecutionHistory;
import tech.kayys.silat.model.NodeId;
import tech.kayys.silat.model.WorkflowRunId;

public interface ExecutionHistoryRepository {

    Uni<Void> append(WorkflowRunId runId, String type, String message, Map<String, Object> metadata);

    Uni<Void> appendEvents(WorkflowRunId runId, java.util.List<tech.kayys.silat.model.event.ExecutionEvent> events);

    Uni<ExecutionHistory> load(WorkflowRunId runId);

    Uni<Boolean> isNodeResultProcessed(WorkflowRunId runId, NodeId nodeId, int attempt);
}