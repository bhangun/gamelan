package tech.kayys.silat.engine;

import java.util.List;
import java.util.Map;
import java.time.Clock;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import tech.kayys.silat.execution.ExecutionHistory;
import tech.kayys.silat.execution.ExternalSignal;
import tech.kayys.silat.execution.NodeExecutionResult;
import tech.kayys.silat.model.CallbackConfig;
import tech.kayys.silat.model.CallbackRegistration;
import tech.kayys.silat.model.CreateRunRequest;
import tech.kayys.silat.model.ErrorInfo;
import tech.kayys.silat.model.ExecutionToken;
import tech.kayys.silat.model.NodeId;
import tech.kayys.silat.model.RunStatus;
import tech.kayys.silat.model.Signal;
import tech.kayys.silat.model.TenantId;
import tech.kayys.silat.model.ValidationResult;
import tech.kayys.silat.model.WorkflowDefinitionId;
import tech.kayys.silat.model.WorkflowRun;
import tech.kayys.silat.model.WorkflowRunId;
import tech.kayys.silat.model.WorkflowRunSnapshot;
import tech.kayys.silat.repository.WorkflowRunRepository;
import tech.kayys.silat.saga.CompensationCoordinator;

/**
 * DefaultWorkflowRunManager
 *
 * - Authoritative orchestrator
 * - Deterministic state transitions
 * - Idempotent node completion
 * - Safe for multi-instance / distributed execution
 */
@ApplicationScoped
public class DefaultWorkflowRunManager implements WorkflowRunManager {

    @Inject
    WorkflowRunRepository runRepository;
    @Inject
    ExecutionHistoryRepository historyRepository;
    @Inject
    ExecutionTokenService tokenService;
    @Inject
    CallbackService callbackService;
    @Inject
    StateTransitionValidator transitionValidator;
    @Inject
    CompensationCoordinator compensationCoordinator;
    @Inject
    Clock clock;

    // ==================== LIFECYCLE ====================

    @Override
    public Uni<WorkflowRun> createRun(CreateRunRequest request, TenantId tenantId) {
        return Uni.createFrom()
                .failure(new UnsupportedOperationException("Requires WorkflowDefinitionRegistry to create run"));
    }

    @Override
    public Uni<WorkflowRun> startRun(WorkflowRunId runId, TenantId tenantId) {
        return runRepository.withLock(runId, run -> {
            run.start();
            return runRepository.update(run)
                    .invoke(() -> historyRepository.append(
                            runId,
                            ExecutionEventTypes.STATUS_CHANGED,
                            RunStatus.RUNNING.name(),
                            Map.of()));
        });
    }

    @Override
    public Uni<WorkflowRun> suspendRun(
            WorkflowRunId runId,
            TenantId tenantId,
            String reason,
            NodeId waitingOnNodeId) {
        return runRepository.withLock(runId, run -> {
            run.suspend(reason, waitingOnNodeId);
            return runRepository.update(run)
                    .invoke(() -> historyRepository.append(
                            runId,
                            ExecutionEventTypes.STATUS_CHANGED,
                            RunStatus.SUSPENDED.name(),
                            Map.of("reason", reason, "waitingOnNode", waitingOnNodeId.value())));
        });
    }

    @Override
    public Uni<WorkflowRun> resumeRun(
            WorkflowRunId runId,
            TenantId tenantId,
            Map<String, Object> resumeData) {
        return runRepository.withLock(runId, run -> {
            run.resume(resumeData);
            return runRepository.update(run)
                    .invoke(() -> historyRepository.append(
                            runId,
                            ExecutionEventTypes.STATUS_CHANGED,
                            RunStatus.RUNNING.name(),
                            resumeData));
        });
    }

    @Override
    public Uni<Void> cancelRun(
            WorkflowRunId runId,
            TenantId tenantId,
            String reason) {
        return runRepository.withLock(runId, run -> {
            run.cancel(reason);
            return runRepository.update(run)
                    .invoke(() -> historyRepository.append(
                            runId,
                            ExecutionEventTypes.STATUS_CHANGED,
                            RunStatus.CANCELLED.name(),
                            Map.of("reason", reason)));
        }).replaceWithVoid();
    }

    @Override
    public Uni<WorkflowRun> completeRun(
            WorkflowRunId runId,
            TenantId tenantId,
            Map<String, Object> outputs) {
        return runRepository.withLock(runId, run -> {
            run.complete(outputs);
            return runRepository.update(run)
                    .invoke(() -> historyRepository.append(
                            runId,
                            ExecutionEventTypes.RUN_COMPLETED,
                            "Run completed",
                            outputs));
        });
    }

    @Override
    public Uni<WorkflowRun> failRun(
            WorkflowRunId runId,
            TenantId tenantId,
            ErrorInfo error) {
        return runRepository.withLock(runId, run -> {

            ValidationResult vr = transitionValidator.validate(run.getStatus(), RunStatus.FAILED);
            if (!vr.isValid()) {
                return Uni.createFrom().failure(new IllegalStateException(vr.message()));
            }

            run.fail(error);

            historyRepository.append(
                    runId,
                    ExecutionEventTypes.RUN_FAILED,
                    error.message(),
                    Map.of("errorCode", error.code()));

            return compensationCoordinator
                    .compensate(run)
                    .replaceWith(run)
                    .flatMap(r -> runRepository.update(r));
        });
    }

    // ==================== NODE FEEDBACK ====================

    @Override
    public Uni<Void> handleNodeResult(
            WorkflowRunId runId,
            NodeExecutionResult result) {
        return runRepository.withLock(runId, run -> {

            // Check if result already processed (idempotency)
            return historyRepository.isNodeResultProcessed(runId, result.nodeId(), result.attempt())
                    .flatMap(processed -> {
                        if (processed) {
                            return Uni.createFrom().voidItem();
                        }

                        return historyRepository.append(
                                runId,
                                ExecutionEventTypes.NODE_COMPLETED,
                                "Node completed",
                                Map.of(
                                        "nodeId", result.nodeId().value(),
                                        "attempt", result.attempt(),
                                        "success",
                                        result.status() == tech.kayys.silat.execution.NodeExecutionStatus.COMPLETED))
                                .chain(() -> {
                                    // Apply result
                                    if (result.status() == tech.kayys.silat.execution.NodeExecutionStatus.COMPLETED) {
                                        run.completeNode(result.nodeId(), result.attempt(),
                                                result.output() != null ? result.output() : Map.of());
                                        return runRepository.update(run).replaceWithVoid();
                                    } else {
                                        run.failNode(result.nodeId(), result.attempt(), result.error());
                                        return runRepository.update(run).replaceWithVoid();
                                    }
                                });
                    });
        });
    }

    @Override
    public Uni<Void> signal(
            WorkflowRunId runId,
            Signal signal) {
        return historyRepository.append(
                runId,
                ExecutionEventTypes.SIGNAL_RECEIVED,
                signal.name(),
                signal.payload());
    }

    // ==================== QUERY ====================

    @Override
    public Uni<WorkflowRun> getRun(WorkflowRunId runId, TenantId tenantId) {
        return runRepository.findById(runId, tenantId);
    }

    @Override
    public Uni<WorkflowRunSnapshot> getSnapshot(
            WorkflowRunId runId,
            TenantId tenantId) {
        return runRepository.snapshot(runId, tenantId);
    }

    @Override
    public Uni<ExecutionHistory> getExecutionHistory(
            WorkflowRunId runId,
            TenantId tenantId) {
        return historyRepository.load(runId);
    }

    @Override
    public Uni<List<WorkflowRun>> queryRuns(
            TenantId tenantId,
            WorkflowDefinitionId definitionId,
            RunStatus status,
            int page,
            int size) {
        return runRepository.query(tenantId, definitionId, status, page, size);
    }

    @Override
    public Uni<Long> getActiveRunsCount(TenantId tenantId) {
        return runRepository.countActiveRuns(tenantId);
    }

    @Override
    public Uni<ValidationResult> validateTransition(
            WorkflowRunId runId,
            RunStatus targetStatus) {
        return runRepository.findById(runId)
                .map(run -> transitionValidator.validate(run.getStatus(), targetStatus));
    }

    // ==================== TOKEN ====================

    @Override
    public Uni<ExecutionToken> createExecutionToken(
            WorkflowRunId runId,
            NodeId nodeId,
            int attempt) {
        return tokenService.issue(runId, nodeId, attempt);
    }

    // ==================== EXTERNAL ====================

    @Override
    public Uni<Void> onNodeExecutionCompleted(
            NodeExecutionResult result,
            String executorSignature) {
        return tokenService.verifySignature(result, executorSignature)
                .flatMap(valid -> valid
                        ? handleNodeResult(result.runId(), result)
                        : Uni.createFrom().failure(new SecurityException("Invalid executor signature")));
    }

    @Override
    public Uni<Void> onExternalSignal(
            WorkflowRunId runId,
            ExternalSignal signal,
            String callbackToken) {
        return callbackService.verify(callbackToken)
                .flatMap(valid -> valid
                        ? signal(runId,
                                new Signal(signal.getSignalType(), signal.getTargetNodeId(), signal.getPayload(),
                                        java.time.Instant.now(clock)))
                        : Uni.createFrom().failure(new SecurityException("Invalid callback token")));
    }

    @Override
    public Uni<CallbackRegistration> registerCallback(
            WorkflowRunId runId,
            NodeId nodeId,
            CallbackConfig config) {
        return callbackService.register(runId, nodeId, config);
    }
}
