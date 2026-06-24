package tech.kayys.gamelan.engine.impl;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import tech.kayys.gamelan.engine.ExecutionEventTypes;
import tech.kayys.gamelan.engine.error.ErrorInfo;
import tech.kayys.gamelan.engine.error.ErrorCode;
import tech.kayys.gamelan.engine.error.GamelanException;
import tech.kayys.gamelan.engine.event.ExecutionEvent;
import tech.kayys.gamelan.engine.event.CompensationFailedEvent;
import tech.kayys.gamelan.engine.event.WorkflowFailedEvent;
import tech.kayys.gamelan.engine.event.WorkflowRunUpdateEvent;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupPublisher;
import tech.kayys.gamelan.engine.node.NodeDispatchReservation;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.node.NodeExecution;
import tech.kayys.gamelan.engine.node.NodeExecutionResult;
import tech.kayys.gamelan.engine.node.NodeExecutionResults;
import tech.kayys.gamelan.engine.node.NodeExecutionStatus;
import tech.kayys.gamelan.engine.node.NodeResultHandlingOutcome;
import tech.kayys.gamelan.engine.run.RunStatus;
import tech.kayys.gamelan.engine.run.CreateRunRequest;
import tech.kayys.gamelan.engine.run.ValidationResult;
import tech.kayys.gamelan.engine.saga.CompensationErrors;
import tech.kayys.gamelan.engine.saga.CompensationState;
import tech.kayys.gamelan.engine.execution.BearerTokenHash;
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
import tech.kayys.gamelan.scheduler.RetryManager;

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

        @Inject
        WorkflowRunWakeupPublisher wakeupPublisher;

        private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(DefaultWorkflowRunManager.class);
        private static final Set<String> SIGNAL_METADATA_RESERVED_KEYS = Set.of(
                        "external",
                        "externalSignalType",
                        "externalSignaturePresent",
                        "externalSource",
                        "clientIdempotencyKeyHash",
                        "idempotencyKey",
                        "payload",
                        "targetNodeId",
                        "timestamp");

        @Inject
        tech.kayys.gamelan.engine.repository.WorkflowRunRepository runRepository;
        @Inject
        tech.kayys.gamelan.engine.execution.ExecutionHistoryRepository historyRepository;
        @Inject
        WorkflowRunCommitService runCommitService;
        @Inject
        WorkflowSignalCommitService signalCommitService;
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
        @Inject
        RetryManager retryManager;

        @Inject
        MeterRegistry meterRegistry;

        private volatile RunFailureMetrics runFailureMetrics;

        // ==================== LIFECYCLE ====================

        @Override
        public Uni<WorkflowRun> createRun(CreateRunRequest request) {
                TenantId tenantId = java.util.Objects.requireNonNull(request.getTenantId(),
                                "TenantId must be set on CreateRunRequest before calling createRun");
                return definitionRegistry.getDefinition(new WorkflowDefinitionId(request.getWorkflowId()), tenantId)
                                .flatMap(definition -> {
                                        WorkflowRun run = WorkflowRun.create(tenantId, definition, request.getInputs());
                                        return runRepository.persist(run)
                                                        .flatMap(persistedRun -> {
                                                                List<ExecutionEvent> events = List.copyOf(
                                                                                persistedRun.getUncommittedEvents());
                                                                return runCommitService().commitEvents(
                                                                                persistedRun,
                                                                                persistedRun.getId(),
                                                                                persistedRun.getTenantId(),
                                                                                events)
                                                                                .replaceWith(persistedRun);
                                                        })
                                                        .flatMap(persistedRun -> {
                                                                if (request.isAutoStart()) {
                                                                        return startRun(persistedRun.getId(), tenantId);
                                                                } else {
                                                                        return publishRunCreated(persistedRun)
                                                                                        .replaceWith(persistedRun);
                                                                }
                                                        });
                                });
        }

        @Override
        public Uni<WorkflowRun> startRun(WorkflowRunId runId, TenantId tenantId) {
                return runRepository.withLock(runId, tenantId, run -> {
                        requireMatchingTenant(runId, tenantId, run);
                        if (run.getStatus() == RunStatus.RUNNING) {
                                return Uni.createFrom().item(run)
                                                .call(() -> publishRunUpdated(
                                                                runId,
                                                                tenantId,
                                                                "run-start-already-active"));
                        }

                        int eventOffset = run.getUncommittedEvents().size();
                        run.start();
                        List<ExecutionEvent> events = newEventsSince(run, eventOffset);
                        RunStatus startedStatus = run.getStatus();
                        return commitRunEvents(run, runId, tenantId, events)
                                        .replaceWith(run)
                                        .call(() -> historyRepository.append(
                                                        runId,
                                                        tenantId,
                                                        ExecutionEventTypes.STATUS_CHANGED,
                                                        startedStatus.name(),
                                                        Map.of()))
                                        .call(() -> {
                                                LOG.debug("Publishing workflow run update event for {}", runId.value());
                                                return publishRunUpdated(
                                                                runId,
                                                                tenantId,
                                                                startWakeupReason(startedStatus));
                                        });
                });
        }

        @Override
        public Uni<WorkflowRun> suspendRun(
                        WorkflowRunId runId,
                        TenantId tenantId,
                        String reason,
                        NodeId waitingOnNodeId) {
                return runRepository.withLock(runId, tenantId, run -> {
                        requireMatchingTenant(runId, tenantId, run);
                        if (run.getStatus() == RunStatus.SUSPENDED) {
                                return Uni.createFrom().item(run)
                                                .call(() -> publishRunUpdated(runId, tenantId,
                                                                "run-suspend-already-suspended"));
                        }

                        int eventOffset = run.getUncommittedEvents().size();
                        run.suspend(reason, waitingOnNodeId);
                        List<ExecutionEvent> events = newEventsSince(run, eventOffset);
                        return commitRunEvents(run, runId, tenantId, events)
                                        .replaceWith(run)
                                        .call(() -> historyRepository.append(
                                                        runId,
                                                        tenantId,
                                                        ExecutionEventTypes.STATUS_CHANGED,
                                                        RunStatus.SUSPENDED.name(),
                                                        suspensionMetadata(reason, waitingOnNodeId)))
                                        .call(() -> publishRunUpdated(runId, tenantId, "run-suspended"));
                });
        }

        @Override
        public Uni<WorkflowRun> resumeRun(
                        WorkflowRunId runId,
                        TenantId tenantId,
                        Map<String, Object> resumeData,
                        String humanTaskId) {
                return runRepository.withLock(runId, tenantId, run -> {
                        requireMatchingTenant(runId, tenantId, run);
                        if (run.getStatus() == RunStatus.RUNNING) {
                                return Uni.createFrom().item(run)
                                                .call(() -> publishRunUpdated(runId, tenantId,
                                                                "run-resume-already-active"));
                        }
                        Map<String, Object> safeResumeData = resumeData != null ? resumeData : Map.of();
                        int eventOffset = run.getUncommittedEvents().size();
                        run.resume(safeResumeData, humanTaskId);
                        List<ExecutionEvent> events = newEventsSince(run, eventOffset);
                        Map<String, Object> metadata = new HashMap<>(safeResumeData);
                        metadata.put("humanTaskId", humanTaskId != null ? humanTaskId : "");

                        return commitRunEvents(run, runId, tenantId, events)
                                        .replaceWith(run)
                                        .call(() -> historyRepository.append(
                                                        runId,
                                                        tenantId,
                                                        ExecutionEventTypes.STATUS_CHANGED,
                                                        RunStatus.RUNNING.name(),
                                                        metadata))
                                        .call(() -> publishRunUpdated(runId, tenantId, "run-resumed"));
                });
        }

        @Override
        public Uni<Void> cancelRun(
                        WorkflowRunId runId,
                        TenantId tenantId,
                        String reason) {
                return runRepository.withLock(runId, tenantId, run -> {
                        requireMatchingTenant(runId, tenantId, run);
                        if (run.getStatus() == RunStatus.CANCELLED) {
                                return Uni.createFrom().item(run)
                                                .call(() -> publishRunUpdated(runId, tenantId,
                                                                "run-cancel-already-terminal"));
                        }
                        int eventOffset = run.getUncommittedEvents().size();
                        run.cancel(reason);
                        List<ExecutionEvent> events = newEventsSince(run, eventOffset);
                        return commitRunEvents(run, runId, tenantId, events)
                                        .replaceWith(run)
                                        .call(() -> historyRepository.append(
                                                        runId,
                                                        tenantId,
                                                        ExecutionEventTypes.STATUS_CHANGED,
                                                        RunStatus.CANCELLED.name(),
                                                        reasonMetadata(reason)))
                                        .call(() -> appendCompensationStartedIfNeeded(runId, tenantId, run))
                                        .call(() -> publishRunUpdated(runId, tenantId, lifecycleWakeupReason(
                                                        "run-cancelled",
                                                        run)));
                }).replaceWithVoid();
        }

        @Override
        public Uni<WorkflowRun> completeRun(
                        WorkflowRunId runId,
                        TenantId tenantId,
                        Map<String, Object> outputs) {
                return runRepository.withLock(runId, tenantId, run -> {
                        requireMatchingTenant(runId, tenantId, run);
                        Map<String, Object> safeOutputs = outputs != null ? outputs : Map.of();
                        if (run.getStatus() == RunStatus.COMPLETED) {
                                return Uni.createFrom().item(run)
                                                .call(() -> publishRunUpdated(runId, tenantId,
                                                                "run-complete-already-terminal"));
                        }
                        int eventOffset = run.getUncommittedEvents().size();
                        run.complete(safeOutputs);
                        List<ExecutionEvent> events = newEventsSince(run, eventOffset);
                        return commitRunEvents(run, runId, tenantId, events)
                                        .replaceWith(run)
                                        .call(() -> historyRepository.append(
                                                        runId,
                                                        tenantId,
                                                        ExecutionEventTypes.RUN_COMPLETED,
                                                        "Run completed",
                                                        safeOutputs))
                                        .call(() -> publishRunUpdated(runId, tenantId, "run-completed"));
                });
        }

        @Override
        public Uni<WorkflowRun> failRun(
                        WorkflowRunId runId,
                        TenantId tenantId,
                        ErrorInfo error) {
                return runRepository.withLock(runId, tenantId, run -> {
                        requireMatchingTenant(runId, tenantId, run);
                        ErrorInfo safeError = normalizeRunFailure(error);
                        if (run.getStatus() == RunStatus.FAILED) {
                                return Uni.createFrom().item(run)
                                                .call(() -> publishRunUpdated(runId, tenantId,
                                                                "run-fail-already-terminal"));
                        }

                        ValidationResult vr = transitionValidator.validate(run.getStatus(), RunStatus.FAILED);
                        if (!vr.isValid()) {
                                return Uni.createFrom().failure(
                                                new GamelanException(ErrorCode.RUN_INVALID_STATE, vr.message()));
                        }

                        RunStatus beforeStatus = run.getStatus();
                        int eventOffset = run.getUncommittedEvents().size();
                        run.fail(safeError);
                        List<ExecutionEvent> events = newEventsSince(run, eventOffset);
                        ErrorInfo terminalError = terminalFailureError(events, safeError);

                        return commitRunEvents(run, runId, tenantId, events)
                                        .replaceWith(run)
                                        .invoke(() -> recordTerminalFailureTransition(
                                                        beforeStatus,
                                                        run,
                                                        terminalError))
                                        .call(() -> historyRepository.append(
                                                        runId,
                                                        tenantId,
                                                        ExecutionEventTypes.RUN_FAILED,
                                                        safeError.message(),
                                                        failureMetadata(safeError)))
                                        .call(() -> appendCompensationStartedIfNeeded(runId, tenantId, run))
                                        .call(() -> publishRunUpdated(runId, tenantId, lifecycleWakeupReason(
                                                        "run-failed",
                                                        run)));
                });
        }

        @Override
        public Uni<Void> completeCompensation(WorkflowRunId runId, TenantId tenantId) {
                return runRepository.withLock(runId, tenantId, run -> {
                        requireMatchingTenant(runId, tenantId, run);
                        if (isCompletedCompensation(run)) {
                                return publishRunUpdated(
                                                runId,
                                                tenantId,
                                                "compensation-complete-already-terminal")
                                                .replaceWith(run);
                        }

                        int eventOffset = run.getUncommittedEvents().size();
                        run.completeCompensation();
                        List<ExecutionEvent> events = newEventsSince(run, eventOffset);
                        return commitRunEvents(run, runId, tenantId, events)
                                        .replaceWith(run)
                                        .call(() -> historyRepository.append(
                                                        runId,
                                                        tenantId,
                                                        ExecutionEventTypes.COMPENSATION_COMPLETED,
                                                        RunStatus.COMPENSATED.name(),
                                                        compensationFinishedMetadata(run.getCompensationState(),
                                                                        RunStatus.COMPENSATED,
                                                                        null)))
                                        .call(() -> publishRunUpdated(runId, tenantId, "compensation-completed"));
                }).replaceWithVoid();
        }

        @Override
        public Uni<Void> failCompensation(WorkflowRunId runId, TenantId tenantId, ErrorInfo error) {
                return runRepository.withLock(runId, tenantId, run -> {
                        requireMatchingTenant(runId, tenantId, run);
                        if (isFailedCompensation(run)) {
                                return publishRunUpdated(
                                                runId,
                                                tenantId,
                                                "compensation-fail-already-terminal")
                                                .replaceWith(run);
                        }

                        ErrorInfo safeError = normalizeCompensationFailure(error);
                        RunStatus beforeStatus = run.getStatus();
                        int eventOffset = run.getUncommittedEvents().size();
                        run.failCompensation(safeError);
                        List<ExecutionEvent> events = newEventsSince(run, eventOffset);
                        ErrorInfo terminalError = terminalFailureError(events, safeError);
                        return commitRunEvents(run, runId, tenantId, events)
                                        .replaceWith(run)
                                        .invoke(() -> recordTerminalFailureTransition(
                                                        beforeStatus,
                                                        run,
                                                        terminalError))
                                        .call(() -> historyRepository.append(
                                                        runId,
                                                        tenantId,
                                                        ExecutionEventTypes.COMPENSATION_FAILED,
                                                        safeError.message(),
                                                        compensationFinishedMetadata(run.getCompensationState(),
                                                                        RunStatus.FAILED,
                                                                        safeError)))
                                        .call(() -> publishRunUpdated(runId, tenantId, "compensation-failed"));
                }).replaceWithVoid();
        }

        // ==================== NODE DISPATCH STATE ====================

        @Override
        public Uni<NodeDispatchReservation> reserveNodeForDispatch(
                        WorkflowRunId runId,
                        TenantId tenantId,
                        NodeId nodeId) {
                return runRepository.withLock(runId, tenantId, run -> {
                        requireMatchingTenant(runId, tenantId, run);
                        if (run.getStatus() != RunStatus.RUNNING) {
                                return Uni.createFrom().item(NodeDispatchReservation.skipped(
                                                runId,
                                                tenantId,
                                                nodeId,
                                                "run-not-running"));
                        }

                        int eventOffset = run.getUncommittedEvents().size();
                        return Uni.createFrom().item(run.reserveNodeForDispatch(nodeId, now()))
                                        .flatMap(reserved -> {
                                                if (reserved.isEmpty()) {
                                                        return Uni.createFrom().item(NodeDispatchReservation.skipped(
                                                                        runId,
                                                                        tenantId,
                                                                        nodeId,
                                                                        "node-not-ready"));
                                                }

                                                int attempt = reserved.get().getAttempt();
                                                List<ExecutionEvent> events = newEventsSince(run, eventOffset);
                                                return commitRunEvents(run, runId, tenantId, events)
                                                                .replaceWith(NodeDispatchReservation.reserved(
                                                                                runId,
                                                                                tenantId,
                                                                                nodeId,
                                                                                attempt));
                                        });
                });
        }

        @Override
        public Uni<Void> failNodeExecution(
                        WorkflowRunId runId,
                        TenantId tenantId,
                        NodeId nodeId,
                        int attempt,
                        ErrorInfo error,
                        String wakeupReason) {
                return runRepository.withLock(runId, tenantId, run -> {
                        requireMatchingTenant(runId, tenantId, run);
                        NodeExecution execution = run.getAllNodeExecutions().get(nodeId);
                        if (run.getStatus().isTerminal()
                                        || execution == null
                                        || execution.getAttempt() != attempt
                                        || execution.getStatus().isTerminal()) {
                                return Uni.createFrom().voidItem();
                        }

                        RunStatus beforeStatus = run.getStatus();
                        ErrorInfo safeError = normalizeNodeFailure(error);
                        int eventOffset = run.getUncommittedEvents().size();
                        run.failNode(nodeId, attempt, safeError);
                        NodeExecution updatedExecution = run.getAllNodeExecutions().get(nodeId);
                        RetryWakeup retryWakeup = retryWakeupFor(updatedExecution);
                        List<ExecutionEvent> events = newEventsSince(run, eventOffset);
                        ErrorInfo terminalError = terminalFailureError(events, safeError);
                        String reason = wakeupReason != null && !wakeupReason.isBlank()
                                        ? wakeupReason
                                        : "node-dispatch-failed";

                        return commitRunEvents(run, runId, tenantId, events)
                                        .invoke(() -> recordTerminalFailureTransition(
                                                        beforeStatus,
                                                        run,
                                                        terminalError))
                                        .call(() -> scheduleRetryWakeup(runId, tenantId, retryWakeup))
                                        .call(() -> publishRunUpdated(runId, tenantId, lifecycleWakeupReason(
                                                        reason,
                                                        run)))
                                        .replaceWithVoid();
                });
        }

        // ==================== NODE FEEDBACK ====================

        @Override
        public Uni<Void> handleNodeResult(
                        WorkflowRunId runId,
                        NodeExecutionResult result) {
                return handleNodeResultWithOutcome(runId, result).replaceWithVoid();
        }

        @Override
        public Uni<NodeResultHandlingOutcome> handleNodeResultWithOutcome(
                        WorkflowRunId runId,
                        NodeExecutionResult result) {
                NodeExecutionResults.validateResultForRun(runId, result);
                return runRepository.withLock(runId, run -> handleLockedNodeResult(runId, run, result));
        }

        @Override
        public Uni<Void> handleNodeResult(
                        WorkflowRunId runId,
                        TenantId tenantId,
                        NodeExecutionResult result) {
                return handleNodeResultWithOutcome(runId, tenantId, result).replaceWithVoid();
        }

        @Override
        public Uni<NodeResultHandlingOutcome> handleNodeResultWithOutcome(
                        WorkflowRunId runId,
                        TenantId tenantId,
                        NodeExecutionResult result) {
                if (tenantId == null) {
                        return handleNodeResultWithOutcome(runId, result);
                }
                NodeExecutionResults.validateResultForRun(runId, result);
                return runRepository.withLock(runId, tenantId, run -> {
                        requireMatchingTenant(runId, tenantId, run);
                        return handleLockedNodeResult(runId, run, result);
                });
        }

        @Override
        public Uni<Void> signal(
                        WorkflowRunId runId,
                        Signal signal) {
                Objects.requireNonNull(signal, "Signal cannot be null");
                Map<String, Object> metadata = signalMetadata(signal);
                String idempotencyKey = rawSignalIdempotencyKey(signal);
                metadata.put("idempotencyKey", idempotencyKey);

                return runRepository.withLock(runId, run -> {
                        TenantId tenantId = run.getTenantId();
                        return applyLockedSignal(
                                        runId,
                                        tenantId,
                                        run,
                                        signal,
                                        idempotencyKey,
                                        metadata,
                                        "signal-received");
                });
        }

        @Override
        public Uni<Void> signal(
                        WorkflowRunId runId,
                        TenantId tenantId,
                        Signal signal) {
                if (tenantId == null) {
                        return signal(runId, signal);
                }
                Objects.requireNonNull(signal, "Signal cannot be null");
                Map<String, Object> metadata = signalMetadata(signal);
                String idempotencyKey = rawSignalIdempotencyKey(signal);
                metadata.put("idempotencyKey", idempotencyKey);

                return runRepository.withLock(runId, tenantId, run -> {
                        requireMatchingTenant(runId, tenantId, run);
                        return applyLockedSignal(
                                        runId,
                                        tenantId,
                                        run,
                                        signal,
                                        idempotencyKey,
                                        metadata,
                                        "signal-received");
                });
        }

        private Uni<Void> applyExternalSignal(
                        WorkflowRunId runId,
                        TenantId tenantId,
                        Signal signal,
                        String idempotencyKey,
                        Map<String, Object> externalMetadata) {
                Objects.requireNonNull(signal, "Signal cannot be null");
                return tenantId != null
                                ? runRepository.withLock(runId, tenantId, run -> {
                                        requireMatchingTenant(runId, tenantId, run);
                                        return applyLockedExternalSignal(
                                                        runId,
                                                        tenantId,
                                                        run,
                                                        signal,
                                                        idempotencyKey,
                                                        externalMetadata);
                                })
                                : runRepository.withLock(runId, run -> applyLockedExternalSignal(
                                                runId,
                                                run.getTenantId(),
                                                run,
                                                signal,
                                                idempotencyKey,
                                                externalMetadata));
        }

        private Uni<Void> applyLockedExternalSignal(
                        WorkflowRunId runId,
                        TenantId tenantId,
                        WorkflowRun run,
                        Signal signal,
                        String idempotencyKey,
                        Map<String, Object> externalMetadata) {
                Map<String, Object> metadata = signalMetadata(signal);
                metadata.putAll(externalMetadata != null ? externalMetadata : Map.of());
                metadata.put("external", true);
                metadata.put("idempotencyKey", idempotencyKey);

                return historyRepository.isExternalSignalProcessed(runId, tenantId, idempotencyKey)
                                .flatMap(processed -> {
                                        if (processed) {
                                                return Uni.createFrom().voidItem();
                                        }
                                        return applyUnprocessedLockedSignal(
                                                        runId,
                                                        tenantId,
                                                        run,
                                                        signal,
                                                        idempotencyKey,
                                                        metadata,
                                                        "external-signal-received");
                                });
        }

        private Uni<Void> applyLockedSignal(
                        WorkflowRunId runId,
                        TenantId tenantId,
                        WorkflowRun run,
                        Signal signal,
                        String idempotencyKey,
                        Map<String, Object> metadata,
                        String publishReason) {
                return historyRepository.isExternalSignalProcessed(runId, tenantId, idempotencyKey)
                                .flatMap(processed -> processed
                                                ? Uni.createFrom().voidItem()
                                                : applyUnprocessedLockedSignal(
                                                                runId,
                                                                tenantId,
                                                                run,
                                                                signal,
                                                                idempotencyKey,
                                                                metadata,
                                                                publishReason));
        }

        private Uni<Void> applyUnprocessedLockedSignal(
                        WorkflowRunId runId,
                        TenantId tenantId,
                        WorkflowRun run,
                        Signal signal,
                        String idempotencyKey,
                        Map<String, Object> metadata,
                        String publishReason) {
                if (!acceptsSignals(run.getStatus())) {
                        return appendIgnoredSignalHistory(
                                        runId,
                                        tenantId,
                                        signal,
                                        run.getStatus(),
                                        metadata)
                                        .chain(() -> historyRepository.markExternalSignalProcessed(
                                                        runId,
                                                        tenantId,
                                                        idempotencyKey))
                                        .replaceWithVoid();
                }

                return signalCommitService().commitAcceptedSignal(
                                run,
                                runId,
                                tenantId,
                                signal,
                                idempotencyKey,
                                metadata)
                                .call(() -> publishRunUpdated(runId, tenantId, publishReason))
                                .replaceWithVoid();
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
                return historyRepository.load(runId, tenantId);
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

        @Override
        public Uni<ExecutionToken> createExecutionToken(
                        WorkflowRunId runId,
                        TenantId tenantId,
                        NodeId nodeId,
                        int attempt) {
                if (tenantId == null) {
                        return createExecutionToken(runId, nodeId, attempt);
                }
                return findTenantRun(runId, tenantId)
                                .flatMap(ignored -> tokenService.issue(runId, tenantId, nodeId, attempt));
        }

        // ==================== EXTERNAL ====================

        @Override
        public Uni<Void> onNodeExecutionCompleted(
                        NodeExecutionResult result,
                        String executorSignature) {
                return onNodeExecutionCompletedWithOutcome(result, null, executorSignature).replaceWithVoid();
        }

        @Override
        public Uni<NodeResultHandlingOutcome> onNodeExecutionCompletedWithOutcome(
                        NodeExecutionResult result,
                        String executorSignature) {
                return onNodeExecutionCompletedWithOutcome(result, null, executorSignature);
        }

        @Override
        public Uni<Void> onNodeExecutionCompleted(
                        NodeExecutionResult result,
                        TenantId tenantId,
                        String executorSignature) {
                return onNodeExecutionCompletedWithOutcome(result, tenantId, executorSignature).replaceWithVoid();
        }

        @Override
        public Uni<NodeResultHandlingOutcome> onNodeExecutionCompletedWithOutcome(
                        NodeExecutionResult result,
                        TenantId tenantId,
                        String executorSignature) {
                TenantId effectiveTenantId = effectiveTenantId(tenantId, result);
                return tokenService.verifySignature(result, tenantId, executorSignature)
                                .flatMap(valid -> valid
                                                ? handleNodeResultForTenant(result, effectiveTenantId)
                                                : Uni.createFrom().failure(
                                                                new SecurityException("Invalid executor signature")));
        }

        @Override
        public Uni<Void> onExternalSignal(
                        WorkflowRunId runId,
                        ExternalSignal signal,
                        String callbackToken) {
                return onExternalSignal(runId, null, signal, callbackToken);
        }

        @Override
        public Uni<Void> onExternalSignal(
                        WorkflowRunId runId,
                        TenantId tenantId,
                        ExternalSignal signal,
                        String callbackToken) {
                Objects.requireNonNull(signal, "ExternalSignal cannot be null");
                String callbackTokenHash = callbackToken != null && !callbackToken.isBlank()
                                ? BearerTokenHash.sha256(callbackToken)
                                : "";
                String signalType = externalSignalType(signal);
                return callbackService.verify(runId, tenantId, callbackToken)
                                .flatMap(valid -> valid
                                                ? applyExternalSignal(
                                                                runId,
                                                                tenantId,
                                                                new Signal(
                                                                                signalType,
                                                                                signal.getTargetNodeId(),
                                                                                signal.getPayload(),
                                                                                signalTimestamp(signal)),
                                                                callbackTokenHash,
                                                                externalSignalMetadata(signal, signalType))
                                                : Uni.createFrom().failure(
                                                                new SecurityException("Invalid callback token")));
        }

        @Override
        public Uni<CallbackRegistration> registerCallback(
                        WorkflowRunId runId,
                        NodeId nodeId,
                        CallbackConfig config) {
                return callbackService.register(runId, nodeId, config);
        }

        @Override
        public Uni<CallbackRegistration> registerCallback(
                        WorkflowRunId runId,
                        TenantId tenantId,
                        NodeId nodeId,
                        CallbackConfig config) {
                if (tenantId == null) {
                        return registerCallback(runId, nodeId, config);
                }
                return findTenantRun(runId, tenantId)
                                .flatMap(ignored -> callbackService.register(runId, tenantId, nodeId, config));
        }

        private Uni<NodeResultHandlingOutcome> handleNodeResultForTenant(
                        NodeExecutionResult result,
                        TenantId tenantId) {
                return tenantId != null
                                ? handleNodeResultWithOutcome(result.runId(), tenantId, result)
                                : handleNodeResultWithOutcome(result.runId(), result);
        }

        private Uni<NodeResultHandlingOutcome> handleLockedNodeResult(
                        WorkflowRunId runId,
                        WorkflowRun run,
                        NodeExecutionResult result) {
                TenantId tenantId = run.getTenantId();
                return historyRepository.isNodeResultProcessed(runId, tenantId, result.nodeId(), result.attempt())
                                .flatMap(processed -> {
                                        NodeExecutionResults.Acceptance acceptance =
                                                        NodeExecutionResults.acceptanceFor(
                                                                        run.getAllNodeExecutions()
                                                                                        .get(result.nodeId()),
                                                                        result,
                                                                        processed);

                                        if (acceptance == NodeExecutionResults.Acceptance.ALREADY_PROCESSED) {
                                                return Uni.createFrom().item(outcome(
                                                                runId,
                                                                tenantId,
                                                                result,
                                                                acceptance,
                                                                false,
                                                                false,
                                                                false,
                                                                false));
                                        }

                                        if (acceptance == NodeExecutionResults.Acceptance.STALE) {
                                                return appendIgnoredNodeResultHistory(
                                                                runId,
                                                                tenantId,
                                                                result,
                                                                acceptance,
                                                                "Stale node result attempt")
                                                                .chain(() -> historyRepository.markNodeResultProcessed(
                                                                                runId,
                                                                                tenantId,
                                                                                result.nodeId(),
                                                                                result.attempt()))
                                                                .map(marked -> outcome(
                                                                                runId,
                                                                                tenantId,
                                                                                result,
                                                                                acceptance,
                                                                                false,
                                                                                true,
                                                                                marked,
                                                                                false));
                                        }

                                        if (acceptance == NodeExecutionResults.Acceptance.ACCEPT
                                                        && !acceptsNodeResults(run.getStatus())) {
                                                NodeExecutionResults.Acceptance ignoredAcceptance =
                                                                NodeExecutionResults.Acceptance.RUN_NOT_ACCEPTING_RESULTS;
                                                return appendIgnoredNodeResultHistory(
                                                                runId,
                                                                tenantId,
                                                                result,
                                                                ignoredAcceptance,
                                                                "Run is not accepting node results: " + run.getStatus())
                                                                .chain(() -> historyRepository.markNodeResultProcessed(
                                                                                runId,
                                                                                tenantId,
                                                                                result.nodeId(),
                                                                                result.attempt()))
                                                                .map(marked -> outcome(
                                                                                runId,
                                                                                tenantId,
                                                                                result,
                                                                                ignoredAcceptance,
                                                                                false,
                                                                                true,
                                                                                marked,
                                                                                false));
                                        }

                                        if (acceptance == NodeExecutionResults.Acceptance.ALREADY_APPLIED) {
                                                return appendNodeResultHistory(runId, tenantId, result, acceptance)
                                                                .chain(() -> historyRepository.markNodeResultProcessed(
                                                                                runId,
                                                                                tenantId,
                                                                                result.nodeId(),
                                                                                result.attempt()))
                                                                .map(marked -> outcome(
                                                                                runId,
                                                                                tenantId,
                                                                                result,
                                                                                acceptance,
                                                                                false,
                                                                                true,
                                                                                marked,
                                                                                false));
                                        }

                                        RunStatus beforeStatus = run.getStatus();
                                        int eventOffset = run.getUncommittedEvents().size();
                                        applyNodeResult(run, result);
                                        RetryWakeup retryWakeup = retryWakeupFor(run, result);
                                        List<ExecutionEvent> events = newEventsSince(run, eventOffset);
                                        ErrorInfo terminalError = terminalFailureError(events, result.error());
                                        return commitRunEvents(run, runId, tenantId, events)
                                                        .replaceWith(run)
                                                        .invoke(() -> recordTerminalFailureTransition(
                                                                        beforeStatus,
                                                                        run,
                                                                        terminalError))
                                                        .call(() -> appendNodeResultHistory(
                                                                        runId,
                                                                        tenantId,
                                                                        result,
                                                                        acceptance))
                                                        .chain(() -> historyRepository.markNodeResultProcessed(
                                                                        runId,
                                                                        tenantId,
                                                                        result.nodeId(),
                                                                        result.attempt()))
                                                        .flatMap(marked -> scheduleRetryWakeup(
                                                                        runId,
                                                                        tenantId,
                                                                        retryWakeup)
                                                                        .call(() -> publishRunUpdated(
                                                                                        run.getId(),
                                                                                        tenantId,
                                                                                        "node-result"))
                                                                        .replaceWith(outcome(
                                                                                        runId,
                                                                                        tenantId,
                                                                                        result,
                                                                                        acceptance,
                                                                                        true,
                                                                                        true,
                                                                                        marked,
                                                                                        retryWakeup.shouldSchedule())));
                                        });
        }

        private boolean acceptsNodeResults(RunStatus status) {
                return status == RunStatus.PENDING
                                || status == RunStatus.RUNNING
                                || status == RunStatus.SUSPENDED;
        }

        private boolean acceptsSignals(RunStatus status) {
                return status != null && !status.isTerminal() && status != RunStatus.COMPENSATING;
        }

        private boolean isCompletedCompensation(WorkflowRun run) {
                CompensationState compensationState = run != null ? run.getCompensationState() : null;
                return run != null
                                && run.getStatus() == RunStatus.COMPENSATED
                                && compensationState != null
                                && compensationState.isComplete();
        }

        private boolean isFailedCompensation(WorkflowRun run) {
                CompensationState compensationState = run != null ? run.getCompensationState() : null;
                return run != null
                                && run.getStatus() == RunStatus.FAILED
                                && compensationState != null
                                && compensationState.isFailed();
        }

        private String startWakeupReason(RunStatus status) {
                return status == RunStatus.RUNNING ? "run-started" : "run-start-pending";
        }

        private String lifecycleWakeupReason(String defaultReason, WorkflowRun run) {
                return run != null && run.getStatus() == RunStatus.COMPENSATING ? "run-compensating" : defaultReason;
        }

        private TenantId effectiveTenantId(TenantId tenantId, NodeExecutionResult result) {
                if (tenantId != null) {
                        return tenantId;
                }
                if (result == null || result.executionToken() == null) {
                        return null;
                }
                return result.executionToken().tenantId();
        }

        private NodeResultHandlingOutcome outcome(
                        WorkflowRunId runId,
                        TenantId tenantId,
                        NodeExecutionResult result,
                        NodeExecutionResults.Acceptance acceptance,
                        boolean runUpdated,
                        boolean historyAppended,
                        boolean processedMarkerWritten,
                        boolean retryWakeupScheduled) {
                return new NodeResultHandlingOutcome(
                                runId,
                                tenantId,
                                result.nodeId(),
                                result.attempt(),
                                acceptance,
                                runUpdated,
                                historyAppended,
                                processedMarkerWritten,
                                retryWakeupScheduled);
        }

        private Uni<WorkflowRun> findTenantRun(WorkflowRunId runId, TenantId tenantId) {
                return runRepository.findById(runId, tenantId)
                                .flatMap(run -> {
                                        if (matchesTenant(run, tenantId)) {
                                                return Uni.createFrom().item(run);
                                        }
                                        return Uni.createFrom().failure(notFound(runId));
                                });
        }

        private void requireMatchingTenant(WorkflowRunId runId, TenantId tenantId, WorkflowRun run) {
                if (!matchesTenant(run, tenantId)) {
                        throw notFound(runId);
                }
        }

        private boolean matchesTenant(WorkflowRun run, TenantId tenantId) {
                return run != null && tenantId != null && tenantId.equals(run.getTenantId());
        }

        private NoSuchElementException notFound(WorkflowRunId runId) {
                return new NoSuchElementException("WorkflowRun not found: " + runId.value());
        }

        private Map<String, Object> signalMetadata(Signal signal) {
                Map<String, Object> metadata = new HashMap<>();
                if (signal.payload() != null) {
                        metadata.put("payload", signal.payload());
                        signal.payload().forEach((key, value) -> {
                                if (!SIGNAL_METADATA_RESERVED_KEYS.contains(key)) {
                                        metadata.put(key, value);
                                }
                        });
                }
                if (signal.idempotencyKey() != null) {
                        metadata.put("clientIdempotencyKeyHash", BearerTokenHash.sha256(signal.idempotencyKey()));
                }
                if (signal.targetNodeId() != null) {
                        metadata.put("targetNodeId", signal.targetNodeId().value());
                }
                if (signal.timestamp() != null) {
                        metadata.put("timestamp", signal.timestamp().toString());
                }
                return metadata;
        }

        private String rawSignalIdempotencyKey(Signal signal) {
                if (signal.idempotencyKey() != null) {
                        return "raw-client:" + BearerTokenHash.sha256(signal.idempotencyKey());
                }
                StringBuilder canonical = new StringBuilder("raw-signal|");
                appendCanonicalValue(canonical, signal.name());
                canonical.append('|');
                appendCanonicalValue(canonical, signal.targetNodeId() != null ? signal.targetNodeId().value() : null);
                canonical.append('|');
                // Transport adapters may stamp receipt time on each retry, so timestamps stay as provenance only.
                appendCanonicalValue(canonical, signal.payload());
                return "raw:" + BearerTokenHash.sha256(canonical.toString());
        }

        private void appendCanonicalValue(StringBuilder canonical, Object value) {
                if (value == null) {
                        canonical.append("null");
                        return;
                }
                if (value instanceof Map<?, ?> map) {
                        canonical.append("map{");
                        map.entrySet().stream()
                                        .sorted((left, right) -> String.valueOf(left.getKey())
                                                        .compareTo(String.valueOf(right.getKey())))
                                        .forEach(entry -> {
                                                appendCanonicalValue(canonical, entry.getKey());
                                                canonical.append('=');
                                                appendCanonicalValue(canonical, entry.getValue());
                                                canonical.append(';');
                                        });
                        canonical.append('}');
                        return;
                }
                if (value instanceof Iterable<?> iterable) {
                        canonical.append("list[");
                        for (Object item : iterable) {
                                appendCanonicalValue(canonical, item);
                                canonical.append(';');
                        }
                        canonical.append(']');
                        return;
                }
                String text = String.valueOf(value);
                canonical.append(value.getClass().getName())
                                .append(':')
                                .append(text.length())
                                .append(':')
                                .append(text);
        }

        private void applyNodeResult(WorkflowRun run, NodeExecutionResult result) {
                if (result.status() == NodeExecutionStatus.COMPLETED) {
                        run.completeNode(
                                        result.nodeId(),
                                        result.attempt(),
                                        result.output() != null ? result.output() : Map.of());
                        return;
                }
                run.failNode(result.nodeId(), result.attempt(), result.error());
        }

        private Uni<Void> commitRunEvents(
                        WorkflowRun run,
                        WorkflowRunId runId,
                        TenantId tenantId,
                        List<ExecutionEvent> events) {
                if (events == null || events.isEmpty()) {
                        return Uni.createFrom().voidItem();
                }
                return runCommitService().commitEvents(run, runId, tenantId, events);
        }

        private WorkflowRunCommitService runCommitService() {
                if (runCommitService != null) {
                        return runCommitService;
                }
                return new WorkflowRunCommitService(runRepository, historyRepository);
        }

        private WorkflowSignalCommitService signalCommitService() {
                if (signalCommitService != null) {
                        return signalCommitService;
                }
                return new WorkflowSignalCommitService(runRepository, historyRepository);
        }

        private List<ExecutionEvent> newEventsSince(WorkflowRun run, int offset) {
                List<ExecutionEvent> events = run.getUncommittedEvents();
                if (offset < 0 || offset >= events.size()) {
                        return List.of();
                }
                return List.copyOf(events.subList(offset, events.size()));
        }

        private Uni<Void> appendNodeResultHistory(
                        WorkflowRunId runId,
                        TenantId tenantId,
                        NodeExecutionResult result,
                        NodeExecutionResults.Acceptance acceptance) {
                boolean success = result.status() == NodeExecutionStatus.COMPLETED;
                Map<String, Object> metadata = new HashMap<>();
                metadata.put("nodeId", result.nodeId().value());
                metadata.put("attempt", result.attempt());
                metadata.put("success", success);
                metadata.put("status", result.status().name());
                metadata.put("acceptance", acceptance.name());
                if (result.error() != null) {
                        metadata.put("errorCode", result.error().code());
                        metadata.put("errorMessage", result.error().message());
                }

                return historyRepository.append(
                                runId,
                                tenantId,
                                success ? ExecutionEventTypes.NODE_COMPLETED : ExecutionEventTypes.NODE_FAILED,
                                success ? "Node completed" : "Node failed",
                                metadata);
        }

        private Uni<Void> appendIgnoredNodeResultHistory(
                        WorkflowRunId runId,
                        TenantId tenantId,
                        NodeExecutionResult result,
                        NodeExecutionResults.Acceptance acceptance,
                        String reason) {
                Map<String, Object> metadata = new HashMap<>();
                metadata.put("nodeId", result.nodeId().value());
                metadata.put("attempt", result.attempt());
                metadata.put("status", result.status().name());
                metadata.put("acceptance", acceptance.name());
                metadata.put("reason", reason);
                if (result.error() != null) {
                        metadata.put("errorCode", result.error().code());
                        metadata.put("errorMessage", result.error().message());
                }

                return historyRepository.append(
                                runId,
                                tenantId,
                                ExecutionEventTypes.NODE_RESULT_IGNORED,
                                reason,
                                metadata);
        }

        private Uni<Void> appendIgnoredSignalHistory(
                        WorkflowRunId runId,
                        TenantId tenantId,
                        Signal signal,
                        RunStatus runStatus,
                        Map<String, Object> metadata) {
                String reason = "Run is not accepting signals: " + runStatus;
                Map<String, Object> ignoredMetadata = new HashMap<>(metadata);
                ignoredMetadata.put("reason", reason);
                ignoredMetadata.put("runStatus", runStatus != null ? runStatus.name() : "");
                if (signal.targetNodeId() != null) {
                        ignoredMetadata.put("targetNodeId", signal.targetNodeId().value());
                }

                return historyRepository.appendSignalIgnoredAudit(
                                runId,
                                tenantId,
                                metadataValue(ignoredMetadata, "idempotencyKey"),
                                reason,
                                ignoredMetadata)
                                .replaceWithVoid();
        }

        private Uni<Void> appendCompensationStartedIfNeeded(
                        WorkflowRunId runId,
                        TenantId tenantId,
                        WorkflowRun run) {
                if (run.getStatus() != RunStatus.COMPENSATING || run.getCompensationState() == null) {
                        return Uni.createFrom().voidItem();
                }
                return historyRepository.append(
                                runId,
                                tenantId,
                                ExecutionEventTypes.COMPENSATION_STARTED,
                                RunStatus.COMPENSATING.name(),
                                compensationStartedMetadata(run.getCompensationState()));
        }

        private Map<String, Object> compensationStartedMetadata(CompensationState compensationState) {
                List<String> nodesToCompensate = nodeValues(compensationState.nodesToCompensate());
                Map<String, Object> metadata = new HashMap<>();
                metadata.put("status", RunStatus.COMPENSATING.name());
                metadata.put("nodesToCompensate", nodesToCompensate);
                metadata.put("nodesToCompensateCount", nodesToCompensate.size());
                metadata.put("compensationStartedAt", compensationState.startedAt().toString());
                return metadata;
        }

        private Map<String, Object> compensationFinishedMetadata(
                        CompensationState compensationState,
                        RunStatus runStatus,
                        ErrorInfo error) {
                Map<String, Object> metadata = new HashMap<>();
                metadata.put("status", runStatus.name());
                if (compensationState != null) {
                        List<String> nodesToCompensate = nodeValues(compensationState.nodesToCompensate());
                        List<String> compensatedNodes = nodeValues(compensationState.compensatedNodes());
                        metadata.put("compensationStatus", compensationState.status().name());
                        metadata.put("nodesToCompensate", nodesToCompensate);
                        metadata.put("nodesToCompensateCount", nodesToCompensate.size());
                        metadata.put("compensatedNodes", compensatedNodes);
                        metadata.put("compensatedNodesCount", compensatedNodes.size());
                        metadata.put("compensationStartedAt", compensationState.startedAt().toString());
                        if (compensationState.completedAt() != null) {
                                metadata.put("compensationCompletedAt", compensationState.completedAt().toString());
                        }
                }
                if (error != null) {
                        metadata.put("errorCode", error.code());
                        if (error.context() != null && !error.context().isEmpty()) {
                                metadata.put("errorContext", error.context());
                        }
                }
                return metadata;
        }

        private List<String> nodeValues(List<NodeId> nodes) {
                return nodes != null ? nodes.stream().map(NodeId::value).toList() : List.of();
        }

        private RetryWakeup retryWakeupFor(WorkflowRun run, NodeExecutionResult result) {
                if (result.status() == NodeExecutionStatus.COMPLETED) {
                        return RetryWakeup.none();
                }
                NodeExecution execution = run.getAllNodeExecutions().get(result.nodeId());
                if (execution == null || !execution.canRetry() || execution.getRetryAt() == null) {
                        return RetryWakeup.none();
                }

                Duration delay = Duration.between(now(), execution.getRetryAt());
                if (delay.isNegative()) {
                        delay = Duration.ZERO;
                }
                return new RetryWakeup(result.nodeId(), execution.getAttempt(), delay);
        }

        private RetryWakeup retryWakeupFor(NodeExecution execution) {
                if (execution == null || !execution.canRetry() || execution.getRetryAt() == null) {
                        return RetryWakeup.none();
                }

                Duration delay = Duration.between(now(), execution.getRetryAt());
                if (delay.isNegative()) {
                        delay = Duration.ZERO;
                }
                return new RetryWakeup(execution.getNodeId(), execution.getAttempt(), delay);
        }

        private Uni<Void> scheduleRetryWakeup(WorkflowRunId runId, TenantId tenantId, RetryWakeup retryWakeup) {
                if (retryWakeup == null || !retryWakeup.shouldSchedule()) {
                        return Uni.createFrom().voidItem();
                }
                if (retryManager == null) {
                        LOG.warn("Retry wake-up requested for run={}, node={} but no RetryManager is available",
                                        runId.value(), retryWakeup.nodeId().value());
                        return Uni.createFrom().voidItem();
                }
                return retryManager.scheduleRetry(
                                runId,
                                tenantId,
                                retryWakeup.nodeId(),
                                retryWakeup.attempt(),
                                retryWakeup.delay())
                                .onFailure().invoke(error -> LOG.warn(
                                                "Retry wake-up scheduling failed for run={}, node={}, attempt={}: {}",
                                                runId.value(),
                                                retryWakeup.nodeId().value(),
                                                retryWakeup.attempt(),
                                                error.getMessage()))
                                .onFailure().recoverWithNull();
        }

        private Instant now() {
                return clock != null ? clock.now() : Instant.now();
        }

        private Instant signalTimestamp(ExternalSignal signal) {
                return signal.getTimestamp() != null ? signal.getTimestamp() : now();
        }

        private String externalSignalType(ExternalSignal signal) {
                String signalType = signal.getSignalType();
                return signalType != null && !signalType.isBlank() ? signalType.trim() : "external_signal";
        }

        private Map<String, Object> externalSignalMetadata(ExternalSignal signal, String signalType) {
                Map<String, Object> metadata = new HashMap<>();
                if (signal.getSource() != null && !signal.getSource().isBlank()) {
                        metadata.put("externalSource", signal.getSource());
                }
                metadata.put("externalSignalType", signalType);
                metadata.put("externalSignaturePresent",
                                signal.getSignature() != null && !signal.getSignature().isBlank());
                return metadata;
        }

        private Map<String, Object> suspensionMetadata(String reason, NodeId waitingOnNodeId) {
                Map<String, Object> metadata = reasonMetadata(reason);
                metadata.put("waitingOnNode", waitingOnNodeId != null ? waitingOnNodeId.value() : "");
                return metadata;
        }

        private Map<String, Object> reasonMetadata(String reason) {
                Map<String, Object> metadata = new HashMap<>();
                metadata.put("reason", reason != null ? reason : "");
                return metadata;
        }

        private String metadataValue(Map<String, Object> metadata, String key) {
                Object value = metadata != null ? metadata.get(key) : null;
                return value != null ? String.valueOf(value) : "";
        }

        private ErrorInfo normalizeNodeFailure(ErrorInfo error) {
                if (error == null) {
                        return new ErrorInfo(
                                        "NODE_DISPATCH_FAILED",
                                        "Node dispatch failed",
                                        "",
                                        Map.of());
                }

                String code = error.code() != null && !error.code().isBlank()
                                ? error.code()
                                : "NODE_DISPATCH_FAILED";
                String message = error.message() != null && !error.message().isBlank()
                                ? error.message()
                                : "Node dispatch failed";
                String stackTrace = error.stackTrace() != null ? error.stackTrace() : "";
                return new ErrorInfo(code, message, stackTrace, error.context());
        }

        private ErrorInfo normalizeRunFailure(ErrorInfo error) {
                if (error == null) {
                        return new ErrorInfo(
                                        "WORKFLOW_FAILED",
                                        "Workflow failed",
                                        "",
                                        Map.of());
                }

                String code = error.code() != null && !error.code().isBlank()
                                ? error.code()
                                : "WORKFLOW_FAILED";
                String message = error.message() != null && !error.message().isBlank()
                                ? error.message()
                                : "Workflow failed";
                String stackTrace = error.stackTrace() != null ? error.stackTrace() : "";
                return new ErrorInfo(code, message, stackTrace, error.context());
        }

        private ErrorInfo normalizeCompensationFailure(ErrorInfo error) {
                return CompensationErrors.normalizeFailure(error);
        }

        private Map<String, Object> failureMetadata(ErrorInfo error) {
                Map<String, Object> metadata = new HashMap<>();
                metadata.put("errorCode", error.code());
                if (error.context() != null && !error.context().isEmpty()) {
                        metadata.put("errorContext", error.context());
                }
                return metadata;
        }

        private Uni<Void> publishRunCreated(WorkflowRun run) {
                if (run == null) {
                        return Uni.createFrom().voidItem();
                }
                if (eventBus != null) {
                        eventBus.publish(
                                        "gamelan.workflow.run.created",
                                        JsonObject.mapFrom(run.createSnapshot()));
                        return Uni.createFrom().voidItem();
                }
                LOG.debug("No Vert.x event bus available for workflow run created event: run={}", run.getId().value());
                return Uni.createFrom().voidItem();
        }

        private ErrorInfo terminalFailureError(List<ExecutionEvent> events, ErrorInfo fallback) {
                if (events != null) {
                        for (int i = events.size() - 1; i >= 0; i--) {
                                ExecutionEvent event = events.get(i);
                                if (event instanceof CompensationFailedEvent failed) {
                                        return failed.error();
                                }
                                if (event instanceof WorkflowFailedEvent failed) {
                                        return failed.error();
                                }
                        }
                }
                return fallback;
        }

        private void recordTerminalFailureTransition(
                        RunStatus beforeStatus,
                        WorkflowRun run,
                        ErrorInfo error) {
                if (run == null || beforeStatus == RunStatus.FAILED || run.getStatus() != RunStatus.FAILED) {
                        return;
                }
                runFailureMetrics().record(error);
        }

        private RunFailureMetrics runFailureMetrics() {
                MeterRegistry registry = meterRegistry;
                if (registry == null) {
                        return RunFailureMetrics.NOOP;
                }

                RunFailureMetrics current = runFailureMetrics;
                if (current == null || current.registry != registry) {
                        synchronized (this) {
                                current = runFailureMetrics;
                                if (current == null || current.registry != registry) {
                                        current = new RunFailureMetrics(registry);
                                        runFailureMetrics = current;
                                }
                        }
                }
                return current;
        }

        private Uni<Void> publishRunUpdated(
                        WorkflowRunId runId,
                        TenantId tenantId,
                        String reason) {
                WorkflowRunUpdateEvent event = WorkflowRunUpdateEvent.of(runId, tenantId, reason);
                if (wakeupPublisher != null) {
                        return wakeupPublisher.publish(event);
                }
                if (eventBus != null) {
                        eventBus.publish(WorkflowRunUpdateEvent.ADDRESS, JsonObject.mapFrom(event));
                        return Uni.createFrom().voidItem();
                }
                LOG.warn("No workflow run wake-up publisher available for run={}, reason={}",
                                runId.value(), event.reason());
                return Uni.createFrom().voidItem();
        }

        private record RetryWakeup(NodeId nodeId, int attempt, Duration delay) {
                static RetryWakeup none() {
                        return new RetryWakeup(null, 0, Duration.ZERO);
                }

                boolean shouldSchedule() {
                        return nodeId != null && attempt > 0 && delay != null && !delay.isZero() && !delay.isNegative();
                }
        }

        private static final class RunFailureMetrics {
                private static final String REASON_WORKFLOW_DEFINITION_INVALID = "workflow_definition_invalid";
                private static final String REASON_WORKFLOW_PLANNING_FAILED = "workflow_planning_failed";
                private static final String REASON_WORKFLOW_STUCK = "workflow_stuck";
                private static final String REASON_CRITICAL_NODE_FAILED = "critical_node_failed";
                private static final String REASON_COMPENSATION_FAILED = "compensation_failed";
                private static final String REASON_DISPATCH_FAILED = "dispatch_failed";
                private static final String REASON_NO_EXECUTOR_AVAILABLE = "no_executor_available";
                private static final String REASON_WORKFLOW_FAILED = "workflow_failed";
                private static final String REASON_UNKNOWN = "unknown";
                private static final String REASON_OTHER = "other";

                private static final RunFailureMetrics NOOP = new RunFailureMetrics();

                private final MeterRegistry registry;
                private final Map<String, Counter> failureCounters;

                private RunFailureMetrics() {
                        this.registry = null;
                        this.failureCounters = Map.of();
                }

                private RunFailureMetrics(MeterRegistry registry) {
                        this.registry = registry;
                        this.failureCounters = new ConcurrentHashMap<>();
                }

                private void record(ErrorInfo error) {
                        if (registry == null) {
                                return;
                        }
                        counter(reason(error)).increment();
                }

                private Counter counter(String reason) {
                        return failureCounters.computeIfAbsent(reason, key -> Counter
                                        .builder("gamelan.workflow.run.failures")
                                        .description("Terminal workflow run failures by bounded reason")
                                        .tag("reason", key)
                                        .register(registry));
                }

                private static String reason(ErrorInfo error) {
                        if (error == null || error.code() == null || error.code().isBlank()) {
                                return REASON_UNKNOWN;
                        }
                        return switch (error.code()) {
                                case "WORKFLOW_DEFINITION_INVALID" -> REASON_WORKFLOW_DEFINITION_INVALID;
                                case "WORKFLOW_PLANNING_FAILED" -> REASON_WORKFLOW_PLANNING_FAILED;
                                case "WORKFLOW_STUCK" -> REASON_WORKFLOW_STUCK;
                                case "CRITICAL_NODE_FAILED" -> REASON_CRITICAL_NODE_FAILED;
                                case CompensationErrors.COMPENSATION_FAILED -> REASON_COMPENSATION_FAILED;
                                case "DISPATCH_FAILED", "NODE_DISPATCH_FAILED", "TASK_DISPATCH_FAILED" ->
                                                REASON_DISPATCH_FAILED;
                                case "NO_EXECUTOR_AVAILABLE" -> REASON_NO_EXECUTOR_AVAILABLE;
                                case "WORKFLOW_FAILED" -> REASON_WORKFLOW_FAILED;
                                case "UNKNOWN_ERROR" -> REASON_UNKNOWN;
                                default -> REASON_OTHER;
                        };
                }
        }
}
