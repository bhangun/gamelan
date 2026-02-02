package tech.kayys.gamelan.engine.impl;

import java.util.List;
import java.util.Map;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import tech.kayys.gamelan.engine.ExecutionEventTypes;
import tech.kayys.gamelan.engine.error.ErrorInfo;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.node.NodeExecutionResult;
import tech.kayys.gamelan.engine.run.RunStatus;
import tech.kayys.gamelan.engine.run.CreateRunRequest;
import tech.kayys.gamelan.engine.run.ValidationResult;
import tech.kayys.gamelan.engine.execution.ExecutionToken;
import tech.kayys.gamelan.engine.signal.Signal;
import tech.kayys.gamelan.engine.signal.ExternalSignal;
import tech.kayys.gamelan.engine.callback.CallbackConfig;
import tech.kayys.gamelan.engine.callback.CallbackRegistration;
import tech.kayys.gamelan.engine.execution.ExecutionHistory;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinitionId;
import tech.kayys.gamelan.engine.workflow.WorkflowRun;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunSnapshot;
import tech.kayys.gamelan.engine.tenant.TenantId;

/**
 * DefaultWorkflowRunManager
 *
 * - Authoritative orchestrator
 * - Deterministic state transitions
 * - Idempotent node completion
 * - Safe for multi-instance / distributed execution
 */
@ApplicationScoped
public class DefaultWorkflowRunManager implements tech.kayys.gamelan.engine.workflow.WorkflowRunManager {

    @Inject
    io.vertx.mutiny.core.eventbus.EventBus eventBus;

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(DefaultWorkflowRunManager.class);

    @Inject
    tech.kayys.gamelan.engine.repository.WorkflowRunRepository runRepository;
    @Inject
    InMemoryExecutionHistoryRepository historyRepository;
    @Inject
    DefaultExecutionTokenService tokenService;
    @Inject
    DefaultCallbackService callbackService;
    @Inject
    StateTransitionValidator transitionValidator;
    @Inject
    tech.kayys.gamelan.core.saga.impl.CompensationCoordinator compensationCoordinator;
    @Inject
    tech.kayys.gamelan.engine.SystemClock clock;
    @Inject
    tech.kayys.gamelan.core.workflow.WorkflowDefinitionRegistry definitionRegistry;

    // ==================== LIFECYCLE ====================

    @Override
    public Uni<WorkflowRun> createRun(CreateRunRequest request, TenantId tenantId) {
        return definitionRegistry.getDefinition(new WorkflowDefinitionId(request.getWorkflowId()), tenantId)
                .flatMap(definition -> {
                    WorkflowRun run = WorkflowRun.create(tenantId, definition, request.getInputs());
                    return runRepository.persist(run)
                            .flatMap(persistedRun -> historyRepository.appendEvents(persistedRun.getId(),
                                    persistedRun.getUncommittedEvents())
                                    .replaceWith(persistedRun))
                            .flatMap(persistedRun -> {
                                if (request.isAutoStart()) {
                                    return startRun(persistedRun.getId(), tenantId);
                                } else {
                                    eventBus.publish("gamelan.workflow.run.created",
                                            io.vertx.core.json.JsonObject.mapFrom(persistedRun.createSnapshot()));
                                    return Uni.createFrom().item(persistedRun);
                                }
                            });
                });
    }

    @Override
    public Uni<WorkflowRun> startRun(WorkflowRunId runId, TenantId tenantId) {
        return runRepository.withLock(runId, run -> {
            run.start();
            return runRepository.update(run)
                    .call(() -> historyRepository.append(
                            runId,
                            ExecutionEventTypes.STATUS_CHANGED,
                            RunStatus.RUNNING.name(),
                            Map.of()))
                    .invoke(() -> {
                        LOG.error("DefaultWorkflowRunManager SEVERE LOG: Publishing updated event for {}",
                                runId.value());
                        eventBus.publish("gamelan.runs.v1.updated", runId.value());
                        LOG.error("DefaultWorkflowRunManager SEVERE LOG: Event published for {}", runId.value());
                    });
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
                    .call(() -> historyRepository.append(
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
            Map<String, Object> resumeData,
            String humanTaskId) {
        return runRepository.withLock(runId, run -> {
            run.resume(resumeData, humanTaskId);
            return runRepository.update(run)
                    .call(() -> historyRepository.append(
                            runId,
                            ExecutionEventTypes.STATUS_CHANGED,
                            RunStatus.RUNNING.name(),
                            java.util.stream.Stream.concat(
                                    resumeData.entrySet().stream(),
                                    java.util.stream.Stream.of(java.util.Map.entry("humanTaskId",
                                            humanTaskId != null ? humanTaskId : "")))
                                    .collect(java.util.stream.Collectors.toMap(
                                            java.util.Map.Entry::getKey,
                                            java.util.Map.Entry::getValue,
                                            (v1, v2) -> v2))));
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
                    .call(() -> historyRepository.append(
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
                    .call(() -> historyRepository.append(
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

            return historyRepository.append(
                    runId,
                    ExecutionEventTypes.RUN_FAILED,
                    error.message(),
                    Map.of("errorCode", error.code()))
                    .chain(() -> runRepository.update(run))
                    .invoke(() -> eventBus.publish("gamelan.runs.v1.updated", runId.value()));
        });
    }

    @Override
    public Uni<Void> completeCompensation(WorkflowRunId runId, TenantId tenantId) {
        return runRepository.withLock(runId, run -> {
            run.completeCompensation();
            return runRepository.update(run)
                    .call(() -> historyRepository.append(
                            runId,
                            ExecutionEventTypes.STATUS_CHANGED,
                            RunStatus.COMPENSATED.name(),
                            Map.of()))
                    .invoke(() -> eventBus.publish("gamelan.runs.v1.updated", runId.value()));
        }).replaceWithVoid();
    }

    @Override
    public Uni<Void> failCompensation(WorkflowRunId runId, TenantId tenantId, ErrorInfo error) {
        return runRepository.withLock(runId, run -> {
            run.failCompensation(error);
            return runRepository.update(run)
                    .call(() -> historyRepository.append(
                            runId,
                            ExecutionEventTypes.RUN_FAILED,
                            "Compensation failed: " + error.message(),
                            Map.of("errorCode", error.code())))
                    .invoke(() -> eventBus.publish("gamelan.runs.v1.updated", runId.value()));
        }).replaceWithVoid();
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
                                        result.status() == tech.kayys.gamelan.engine.node.NodeExecutionStatus.COMPLETED))
                                .chain(() -> {
                                    // Apply result
                                    if (result
                                            .status() == tech.kayys.gamelan.engine.node.NodeExecutionStatus.COMPLETED) {
                                        run.completeNode(result.nodeId(), result.attempt(),
                                                result.output() != null ? result.output() : Map.of());
                                        return runRepository.update(run)
                                                .invoke(() -> eventBus.publish("gamelan.runs.v1.updated",
                                                        runId.value()))
                                                .replaceWithVoid();
                                    } else {
                                        run.failNode(result.nodeId(), result.attempt(), result.error());
                                        return runRepository.update(run)
                                                .invoke(() -> eventBus.publish("gamelan.runs.v1.updated",
                                                        runId.value()))
                                                .replaceWithVoid();
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
                                        java.time.Instant.now(clock.asJavaClock())))
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
