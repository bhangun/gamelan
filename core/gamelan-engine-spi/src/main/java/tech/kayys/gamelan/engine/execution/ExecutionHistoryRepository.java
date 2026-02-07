package tech.kayys.gamelan.engine.execution;

import java.util.Map;

import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;

public interface ExecutionHistoryRepository {

    Uni<Void> append(WorkflowRunId runId, String type, String message, Map<String, Object> metadata);

    Uni<Void> appendEvents(WorkflowRunId runId, java.util.List<tech.kayys.gamelan.engine.event.ExecutionEvent> events);

    Uni<ExecutionHistory> load(WorkflowRunId runId);

    Uni<Boolean> isNodeResultProcessed(WorkflowRunId runId, NodeId nodeId, int attempt);
}