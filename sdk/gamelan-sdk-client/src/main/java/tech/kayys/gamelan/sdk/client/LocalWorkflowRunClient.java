package tech.kayys.gamelan.sdk.client;

import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.engine.run.RunResponse;
import tech.kayys.gamelan.engine.run.CreateRunRequest;
import tech.kayys.gamelan.engine.run.RunStatus;
import tech.kayys.gamelan.engine.execution.ExecutionHistory;
import tech.kayys.gamelan.engine.workflow.WorkflowRun;
import tech.kayys.gamelan.engine.workflow.WorkflowRunManager;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinitionId;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.signal.Signal;

import java.time.Instant;
import java.time.Duration;
import java.util.Map;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Local-based implementation of {@link WorkflowRunClient}.
 * This transport allows direct execution within the same JVM, bypassing the
 * network.
 */
public class LocalWorkflowRunClient implements WorkflowRunClient {

    private final WorkflowRunManager runManager;
    private final TenantId tenantId;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public LocalWorkflowRunClient(WorkflowRunManager runManager, String tenantId) {
        this.runManager = runManager;
        this.tenantId = TenantId.of(tenantId);
    }

    @Override
    public Uni<RunResponse> createRun(CreateRunRequest request) {
        checkClosed();
        if (runManager == null) {
            return Uni.createFrom()
                    .failure(new IllegalStateException("WorkflowRunManager not provided for LOCAL transport"));
        }
        return runManager.createRun(request, tenantId)
                .flatMap(run -> {
                    if (request.isAutoStart()) {
                        return runManager.startRun(run.getId(), tenantId);
                    }
                    return Uni.createFrom().item(run);
                })
                .map(this::toResponse);
    }

    @Override
    public Uni<RunResponse> getRun(String runId) {
        checkClosed();
        return runManager.getRun(WorkflowRunId.of(runId), tenantId)
                .map(this::toResponse);
    }

    @Override
    public Uni<RunResponse> startRun(String runId) {
        checkClosed();
        return runManager.startRun(WorkflowRunId.of(runId), tenantId)
                .map(this::toResponse);
    }

    @Override
    public Uni<RunResponse> suspendRun(String runId, String reason, String waitingOnNodeId) {
        checkClosed();
        tech.kayys.gamelan.engine.node.NodeId node = waitingOnNodeId != null
                ? tech.kayys.gamelan.engine.node.NodeId.of(waitingOnNodeId)
                : null;
        return runManager.suspendRun(WorkflowRunId.of(runId), tenantId, reason, node)
                .map(this::toResponse);
    }

    @Override
    public Uni<RunResponse> resumeRun(String runId, Map<String, Object> resumeData, String humanTaskId) {
        checkClosed();
        return runManager.resumeRun(WorkflowRunId.of(runId), tenantId, resumeData, humanTaskId)
                .map(this::toResponse);
    }

    @Override
    public Uni<Void> cancelRun(String runId, String reason) {
        checkClosed();
        return runManager.cancelRun(WorkflowRunId.of(runId), tenantId, reason);
    }

    @Override
    public Uni<Void> signal(String runId, String signalName, String targetNodeId, Map<String, Object> payload) {
        checkClosed();
        Signal signal = new Signal(signalName,
                targetNodeId != null ? tech.kayys.gamelan.engine.node.NodeId.of(targetNodeId) : null, payload,
                Instant.now());
        return runManager.signal(WorkflowRunId.of(runId), signal);
    }

    @Override
    public Uni<ExecutionHistory> getExecutionHistory(String runId) {
        checkClosed();
        return runManager.getExecutionHistory(WorkflowRunId.of(runId), tenantId);
    }

    @Override
    public Uni<List<RunResponse>> queryRuns(String workflowId, String status, int page, int size) {
        checkClosed();
        WorkflowDefinitionId defId = workflowId != null ? WorkflowDefinitionId.of(workflowId) : null;
        RunStatus runStatus = status != null ? RunStatus.valueOf(status) : null;
        return runManager.queryRuns(tenantId, defId, runStatus, page, size)
                .map(runs -> runs.stream().map(this::toResponse).collect(Collectors.toList()));
    }

    @Override
    public Uni<Long> getActiveRunsCount() {
        checkClosed();
        return runManager.getActiveRunsCount(tenantId);
    }

    private RunResponse toResponse(WorkflowRun run) {
        if (run == null)
            return null;

        Long durationMs = null;
        if (run.getStartedAt() != null && run.getCompletedAt() != null) {
            durationMs = Duration.between(run.getStartedAt(), run.getCompletedAt()).toMillis();
        }

        return RunResponse.builder()
                .runId(run.getId().value())
                .workflowId(run.getDefinitionId().value())
                .status(run.getStatus().name())
                .createdAt(run.getCreatedAt())
                .startedAt(run.getStartedAt())
                .completedAt(run.getCompletedAt())
                .durationMs(durationMs)
                .outputs(run.getContext().getVariables()) // Simplified, might want filtered outputs
                .nodesExecuted(run.getAllNodeExecutions().size()) // Simplified
                .build();
    }

    private void checkClosed() {
        if (closed.get()) {
            throw new IllegalStateException("Client is closed");
        }
    }

    @Override
    public void close() {
        closed.set(true);
    }
}
