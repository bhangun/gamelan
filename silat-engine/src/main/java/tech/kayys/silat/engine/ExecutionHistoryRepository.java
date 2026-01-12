package tech.kayys.silat.engine;

import java.util.Map;

import io.smallrye.mutiny.Uni;
import tech.kayys.silat.execution.ExecutionHistory;
import tech.kayys.silat.model.NodeId;
import tech.kayys.silat.model.WorkflowRunId;

interface ExecutionHistoryRepository {

    Uni<Void> append(WorkflowRunId runId, String type, String message, Map<String, Object> metadata);

    Uni<ExecutionHistory> load(WorkflowRunId runId);

    Uni<Boolean> isNodeResultProcessed(WorkflowRunId runId, NodeId nodeId, int attempt);
}