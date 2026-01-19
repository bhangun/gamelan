package tech.kayys.gamelan.sdk.client;

import java.util.Map;
import java.util.List;

import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.engine.run.CreateRunRequest;
import tech.kayys.gamelan.engine.run.RunResponse;
import tech.kayys.gamelan.engine.execution.ExecutionHistory;

/**
 * Workflow run client interface (transport-agnostic)
 */
interface WorkflowRunClient extends AutoCloseable {
    Uni<RunResponse> createRun(CreateRunRequest request);

    Uni<RunResponse> getRun(String runId);

    Uni<RunResponse> startRun(String runId);

    Uni<RunResponse> suspendRun(String runId, String reason, String waitingOnNodeId);

    Uni<RunResponse> resumeRun(String runId, Map<String, Object> resumeData, String humanTaskId);

    Uni<Void> cancelRun(String runId, String reason);

    Uni<Void> signal(String runId, String signalName, String targetNodeId, Map<String, Object> payload);

    Uni<ExecutionHistory> getExecutionHistory(String runId);

    Uni<List<RunResponse>> queryRuns(String workflowId, String status, int page, int size);

    Uni<Long> getActiveRunsCount();

    @Override
    void close();
}