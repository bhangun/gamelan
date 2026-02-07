package tech.kayys.gamelan.sdk.client;

import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.engine.execution.ExecutionHistory;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.run.CreateRunRequest;
import tech.kayys.gamelan.engine.run.RunResponse;
import tech.kayys.gamelan.engine.run.RunStatus;
import tech.kayys.gamelan.engine.signal.Signal;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinitionId;
import tech.kayys.gamelan.engine.workflow.WorkflowRun;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunManager;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Local implementation of {@link WorkflowRunClient} that directly calls the engine services.
 */
public class LocalWorkflowRunClient implements WorkflowRunClient {

    private final WorkflowRunManager runManager;
    private final TenantId tenantId;

    public LocalWorkflowRunClient(WorkflowRunManager runManager, String tenantId) {
        this.runManager = runManager;
        this.tenantId = TenantId.of(tenantId);
    }

    @Override
    public Uni<RunResponse> createRun(CreateRunRequest request) {
        return runManager.createRun(request, tenantId)
                .map(this::mapToResponse);
    }

    @Override
    public Uni<RunResponse> getRun(String runId) {
        return runManager.getRun(WorkflowRunId.of(runId), tenantId)
                .map(this::mapToResponse);
    }

    @Override
    public Uni<RunResponse> startRun(String runId) {
        return runManager.startRun(WorkflowRunId.of(runId), tenantId)
                .map(this::mapToResponse);
    }

    @Override
    public Uni<RunResponse> suspendRun(String runId, String reason, String waitingOnNodeId) {
        return runManager.suspendRun(WorkflowRunId.of(runId), tenantId, reason, 
                waitingOnNodeId != null ? NodeId.of(waitingOnNodeId) : null)
                .map(this::mapToResponse);
    }

    @Override
    public Uni<RunResponse> resumeRun(String runId, Map<String, Object> resumeData, String humanTaskId) {
        return runManager.resumeRun(WorkflowRunId.of(runId), tenantId, resumeData, humanTaskId)
                .map(this::mapToResponse);
    }

    @Override
    public Uni<Void> cancelRun(String runId, String reason) {
        return runManager.cancelRun(WorkflowRunId.of(runId), tenantId, reason);
    }

    @Override
    public Uni<Void> signal(String runId, String signalName, String targetNodeId, Map<String, Object> payload) {
        Signal signal = new Signal(signalName, targetNodeId != null ? NodeId.of(targetNodeId) : null, payload);
        return runManager.signal(WorkflowRunId.of(runId), signal);
    }

    @Override
    public Uni<ExecutionHistory> getExecutionHistory(String runId) {
        return runManager.getExecutionHistory(WorkflowRunId.of(runId), tenantId);
    }

    @Override
    public Uni<List<RunResponse>> queryRuns(String workflowId, String status, int page, int size) {
        return runManager.queryRuns(
                tenantId,
                workflowId != null ? WorkflowDefinitionId.of(workflowId) : null,
                status != null ? RunStatus.valueOf(status) : null,
                page,
                size)
                .map(runs -> runs.stream().map(this::mapToResponse).collect(Collectors.toList()));
    }

    @Override
    public Uni<Long> getActiveRunsCount() {
        return runManager.getActiveRunsCount(tenantId);
    }

    @Override
    public void close() {
        // No-op for local client as it doesn't own the runManager
    }

    private RunResponse mapToResponse(WorkflowRun run) {
        Duration duration = run.getStartedAt() != null && run.getCompletedAt() != null 
                ? Duration.between(run.getStartedAt(), run.getCompletedAt()) 
                : Duration.ZERO;

        return RunResponse.builder()
                .runId(run.getId().value())
                .workflowId(run.getDefinitionId().value())
                .status(run.getStatus().name())
                .createdAt(run.getCreatedAt())
                .startedAt(run.getStartedAt())
                .completedAt(run.getCompletedAt())
                .durationMs(duration.toMillis())
                .outputs(run.getContext().getVariables())
                .build();
    }
}
