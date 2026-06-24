package tech.kayys.gamelan.sdk.client;

import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.engine.execution.ExecutionHistory;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.run.CreateRunRequest;
import tech.kayys.gamelan.engine.run.RunResponse;
import tech.kayys.gamelan.engine.run.RunStatus;
import tech.kayys.gamelan.engine.signal.Signal;
import tech.kayys.gamelan.engine.tenant.TenantId;
import java.time.Instant;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinitionId;
import tech.kayys.gamelan.engine.workflow.WorkflowRun;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunManager;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Local implementation of {@link WorkflowRunClient} that directly calls the engine services.
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
        return runManager().createRun(request, tenantId)
                .flatMap(run -> request.isAutoStart()
                        ? runManager().startRun(run.getId(), tenantId)
                        : Uni.createFrom().item(run))
                .map(this::mapToResponse);
    }

    @Override
    public Uni<RunResponse> getRun(String runId) {
        checkClosed();
        return runManager().getRun(WorkflowRunId.of(runId), tenantId)
                .map(this::mapToResponse);
    }

    @Override
    public Uni<RunResponse> startRun(String runId) {
        checkClosed();
        return runManager().startRun(WorkflowRunId.of(runId), tenantId)
                .map(this::mapToResponse);
    }

    @Override
    public Uni<RunResponse> suspendRun(String runId, String reason, String waitingOnNodeId) {
        checkClosed();
        return runManager().suspendRun(WorkflowRunId.of(runId), tenantId, reason,
                waitingOnNodeId != null && !waitingOnNodeId.isBlank() ? NodeId.of(waitingOnNodeId.trim()) : null)
                .map(this::mapToResponse);
    }

    @Override
    public Uni<RunResponse> resumeRun(String runId, Map<String, Object> resumeData, String humanTaskId) {
        checkClosed();
        return runManager().resumeRun(WorkflowRunId.of(runId), tenantId, resumeData, humanTaskId)
                .map(this::mapToResponse);
    }

    @Override
    public Uni<Void> cancelRun(String runId, String reason) {
        checkClosed();
        return runManager().cancelRun(WorkflowRunId.of(runId), tenantId, reason);
    }

    @Override
    public Uni<Void> signal(String runId, String signalName, String targetNodeId, Map<String, Object> payload) {
        return signal(runId, signalName, targetNodeId, payload, null);
    }

    @Override
    public Uni<Void> signal(
            String runId,
            String signalName,
            String targetNodeId,
            Map<String, Object> payload,
            String idempotencyKey) {
        checkClosed();
        Signal signal = new Signal(
                signalName,
                targetNodeId != null && !targetNodeId.isBlank() ? NodeId.of(targetNodeId.trim()) : null,
                payload,
                Instant.now(),
                idempotencyKey);
        return runManager().signal(WorkflowRunId.of(runId), signal);
    }

    @Override
    public Uni<ExecutionHistory> getExecutionHistory(String runId) {
        checkClosed();
        return runManager().getExecutionHistory(WorkflowRunId.of(runId), tenantId);
    }

    @Override
    public Uni<List<RunResponse>> queryRuns(String workflowId, String status, int page, int size) {
        checkClosed();
        return runManager().queryRuns(
                tenantId,
                workflowId != null ? WorkflowDefinitionId.of(workflowId) : null,
                status != null ? RunStatus.valueOf(status) : null,
                page,
                size)
                .map(runs -> runs.stream().map(this::mapToResponse).collect(Collectors.toList()));
    }

    @Override
    public Uni<Long> getActiveRunsCount() {
        checkClosed();
        return runManager().getActiveRunsCount(tenantId);
    }

    @Override
    public void close() {
        closed.set(true);
    }

    private RunResponse mapToResponse(WorkflowRun run) {
        Long durationMs = run.getStartedAt() != null && run.getCompletedAt() != null
                ? Duration.between(run.getStartedAt(), run.getCompletedAt()).toMillis()
                : null;

        return RunResponse.builder()
                .runId(run.getId().value())
                .workflowId(run.getDefinitionId().value())
                .status(run.getStatus().name())
                .createdAt(run.getCreatedAt())
                .startedAt(run.getStartedAt())
                .completedAt(run.getCompletedAt())
                .durationMs(durationMs)
                .outputs(run.getContext().getVariables())
                .nodesExecuted(run.getAllNodeExecutions().size())
                .build();
    }

    private WorkflowRunManager runManager() {
        if (runManager == null) {
            throw new IllegalStateException("WorkflowRunManager not provided for LOCAL transport");
        }
        return runManager;
    }

    private void checkClosed() {
        if (closed.get()) {
            throw new IllegalStateException("Client is closed");
        }
    }
}
