package tech.kayys.gamelan.engine.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.Vertx;
import tech.kayys.gamelan.core.workflow.WorkflowDefinitionRegistry;
import tech.kayys.gamelan.engine.ExecutionEventTypes;
import tech.kayys.gamelan.engine.callback.CallbackConfig;
import tech.kayys.gamelan.engine.callback.CallbackRegistration;
import tech.kayys.gamelan.engine.error.ErrorCode;
import tech.kayys.gamelan.engine.error.ErrorInfo;
import tech.kayys.gamelan.engine.error.GamelanException;
import tech.kayys.gamelan.engine.event.CompensationCompletedEvent;
import tech.kayys.gamelan.engine.event.CompensationFailedEvent;
import tech.kayys.gamelan.engine.event.CompensationStartedEvent;
import tech.kayys.gamelan.engine.event.ExecutionEvent;
import tech.kayys.gamelan.engine.event.NodeCompletedEvent;
import tech.kayys.gamelan.engine.event.NodeFailedEvent;
import tech.kayys.gamelan.engine.event.NodeScheduledEvent;
import tech.kayys.gamelan.engine.event.NodeStartedEvent;
import tech.kayys.gamelan.engine.event.WorkflowCancelledEvent;
import tech.kayys.gamelan.engine.event.WorkflowCompletedEvent;
import tech.kayys.gamelan.engine.event.WorkflowFailedEvent;
import tech.kayys.gamelan.engine.event.WorkflowResumedEvent;
import tech.kayys.gamelan.engine.event.WorkflowStartedEvent;
import tech.kayys.gamelan.engine.event.WorkflowSuspendedEvent;
import tech.kayys.gamelan.engine.event.WorkflowRunUpdateEvent;
import tech.kayys.gamelan.engine.execution.BearerTokenHash;
import tech.kayys.gamelan.engine.execution.ExecutionHistory;
import tech.kayys.gamelan.engine.execution.ExecutionHistoryRepository;
import tech.kayys.gamelan.engine.execution.ExecutionToken;
import tech.kayys.gamelan.engine.execution.ExecutionTokenHash;
import tech.kayys.gamelan.engine.node.DefaultNodeExecutionResult;
import tech.kayys.gamelan.engine.node.NodeDispatchReservation;
import tech.kayys.gamelan.engine.node.InputDefinition;
import tech.kayys.gamelan.engine.node.NodeDefinition;
import tech.kayys.gamelan.engine.node.NodeExecutionSnapshot;
import tech.kayys.gamelan.engine.node.NodeExecutionStatus;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.node.NodeType;
import tech.kayys.gamelan.engine.node.NodeResultHandlingOutcome;
import tech.kayys.gamelan.engine.node.NodeExecutionResults;
import tech.kayys.gamelan.engine.repository.WorkflowRunRepository;
import tech.kayys.gamelan.engine.run.CreateRunRequest;
import tech.kayys.gamelan.engine.run.RetryPolicy;
import tech.kayys.gamelan.engine.run.RunStatus;
import tech.kayys.gamelan.engine.saga.CompensationErrors;
import tech.kayys.gamelan.engine.saga.CompensationPolicy;
import tech.kayys.gamelan.engine.signal.ExternalSignal;
import tech.kayys.gamelan.engine.signal.Signal;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinition;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinitionId;
import tech.kayys.gamelan.engine.workflow.WorkflowMode;
import tech.kayys.gamelan.engine.workflow.WorkflowRun;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunSnapshot;
import tech.kayys.gamelan.scheduler.RetryManager;

class DefaultWorkflowRunManagerTest {

    private static final TenantId TENANT = TenantId.of("test-tenant");

    private final NodeId nodeId = NodeId.of("node-1");
    private final RecordingWorkflowRunRepository runRepository = new RecordingWorkflowRunRepository();
    private final RecordingExecutionHistoryRepository historyRepository = new RecordingExecutionHistoryRepository();

    private DefaultWorkflowRunManager runManager;
    private Vertx vertx;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
        meterRegistry = new SimpleMeterRegistry();
        runManager = new DefaultWorkflowRunManager();
        runManager.eventBus = vertx.eventBus();
        runManager.runRepository = runRepository;
        runManager.historyRepository = historyRepository;
        runManager.transitionValidator = new StateTransitionValidator();
        DefaultExecutionTokenService tokenService = new DefaultExecutionTokenService();
        tokenService.runRepository = runRepository;
        runManager.tokenService = tokenService;
        runManager.callbackService = new DefaultCallbackService(runRepository);
        runManager.meterRegistry = meterRegistry;
    }

    @AfterEach
    void tearDown() {
        vertx.close().await().indefinitely();
        meterRegistry.close();
    }

    @Test
    void createRun_propagatesDefinitionLoadFailure() {
        runManager.definitionRegistry = new FailingDefinitionRegistry();
        CreateRunRequest request = CreateRunRequest.builder()
                .workflowId("test-def")
                .workflowVersion("1.0.0")
                .inputs(Map.of())
                .correlationId("cor-id")
                .autoStart(true)
                .tenantId(TENANT)
                .build();

        assertThrows(UnsupportedOperationException.class,
                () -> runManager.createRun(request).await().indefinitely());
    }

    @Test
    void createRun_commitsPersistedCreationEvents() {
        runManager.definitionRegistry = new FixedDefinitionRegistry(workflow());
        CreateRunRequest request = CreateRunRequest.builder()
                .workflowId("wf-test")
                .workflowVersion("1.0.0")
                .inputs(Map.of())
                .correlationId("cor-id")
                .autoStart(false)
                .tenantId(TENANT)
                .build();

        WorkflowRun run = runManager.createRun(request).await().indefinitely();

        assertTrue(run.getUncommittedEvents().isEmpty());
        assertEquals(1, run.getVersion());
        assertEquals(1, runRepository.updateCount.get());
        assertEquals(1, historyRepository.eventBatches.size());
        assertTrue(historyRepository.eventBatches.getFirst().events().stream()
                .anyMatch(WorkflowStartedEvent.class::isInstance));
    }

    @Test
    void createRun_withoutEventBusStillPersistsNonAutoStartRun() {
        runManager.definitionRegistry = new FixedDefinitionRegistry(workflow());
        runManager.eventBus = null;
        CreateRunRequest request = CreateRunRequest.builder()
                .workflowId("wf-test")
                .workflowVersion("1.0.0")
                .inputs(Map.of())
                .correlationId("cor-id")
                .autoStart(false)
                .tenantId(TENANT)
                .build();

        WorkflowRun run = runManager.createRun(request).await().indefinitely();

        assertEquals(RunStatus.CREATED, run.getStatus());
        assertTrue(run.getUncommittedEvents().isEmpty());
        assertEquals(1, run.getVersion());
        assertEquals(1, runRepository.updateCount.get());
        assertEquals(1, historyRepository.eventBatches.size());
    }

    @Test
    void createRun_rejectsInvalidDefinitionBeforePersistingRun() {
        runManager.definitionRegistry = new FixedDefinitionRegistry(legacyWorkflowWithoutNodes());
        CreateRunRequest request = CreateRunRequest.builder()
                .workflowId("wf-test")
                .workflowVersion("1.0.0")
                .inputs(Map.of())
                .correlationId("cor-id")
                .autoStart(false)
                .tenantId(TENANT)
                .build();

        GamelanException error = assertThrows(GamelanException.class,
                () -> runManager.createRun(request).await().indefinitely());

        assertEquals(ErrorCode.WORKFLOW_INVALID_DEFINITION, error.getErrorCode());
        assertTrue(error.getSafeMessage().contains("Workflow must have at least one node"));
        assertNull(runRepository.run);
        assertEquals(0, runRepository.updateCount.get());
        assertTrue(historyRepository.eventBatches.isEmpty());
        assertTrue(historyRepository.appends.isEmpty());
    }

    @Test
    void reserveNodeForDispatch_reservesAttemptAndAppendsStartedEvent() {
        WorkflowRun run = WorkflowRun.create(TENANT, workflow(node()), Map.of());
        run.start();
        run.markEventsAsCommitted();
        long initialVersion = run.getVersion();
        runRepository.run = run;

        NodeDispatchReservation reservation = runManager.reserveNodeForDispatch(run.getId(), TENANT, nodeId)
                .await().indefinitely();

        assertTrue(reservation.reserved());
        assertEquals(1, reservation.attempt());
        assertEquals(TENANT, reservation.tenantId());
        assertEquals(TENANT, runRepository.lastLockTenant);
        assertEquals(1, runRepository.updateCount.get());
        assertEquals(NodeExecutionStatus.RUNNING, run.getNodeExecution(nodeId).getStatus());
        assertEquals(1, historyRepository.eventBatches.size());
        List<ExecutionEvent> events = historyRepository.eventBatches.getFirst().events();
        assertEquals(1, events.size());
        assertTrue(events.getFirst() instanceof NodeStartedEvent);
        assertTrue(run.getUncommittedEvents().isEmpty());
        assertEquals(initialVersion + 1, run.getVersion());
    }

    @Test
    void startRun_doesNotPersistRunOrSecondaryHistoryWhenEventCommitFails() {
        RuntimeException failure = new RuntimeException("history unavailable");
        WorkflowRun run = WorkflowRun.create(TENANT, workflow(node()), Map.of());
        run.markEventsAsCommitted();
        runRepository.run = run;
        historyRepository.eventAppendFailure = failure;

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> runManager.startRun(run.getId(), TENANT).await().indefinitely());

        assertSame(failure, thrown);
        assertEquals(0, runRepository.updateCount.get());
        assertTrue(historyRepository.eventBatches.isEmpty());
        assertTrue(historyRepository.appends.isEmpty());
        assertFalse(run.getUncommittedEvents().isEmpty());
    }

    @Test
    void reserveNodeForDispatch_skipsAlreadyRunningAttemptWithoutMutation() {
        WorkflowRun run = runningRun();
        runRepository.run = run;

        NodeDispatchReservation reservation = runManager.reserveNodeForDispatch(run.getId(), TENANT, nodeId)
                .await().indefinitely();

        assertFalse(reservation.reserved());
        assertEquals("node-not-ready", reservation.reason());
        assertEquals(0, runRepository.updateCount.get());
        assertTrue(historyRepository.eventBatches.isEmpty());
    }

    @Test
    void failNodeExecution_persistsFailureEventsAndPublishesWakeup() {
        RecordingWakeupPublisher wakeupPublisher = new RecordingWakeupPublisher();
        runManager.wakeupPublisher = wakeupPublisher;
        WorkflowRun run = WorkflowRun.create(TENANT, workflow(criticalNode()), Map.of());
        run.start();
        run.startNode(nodeId, 1);
        run.markEventsAsCommitted();
        long initialVersion = run.getVersion();
        runRepository.run = run;

        runManager.failNodeExecution(
                run.getId(),
                TENANT,
                nodeId,
                1,
                new ErrorInfo("DISPATCH_FAILED", "broker unavailable", "", Map.of()),
                "dispatch-failed").await().indefinitely();

        assertEquals(1, runRepository.updateCount.get());
        assertEquals(RunStatus.FAILED, run.getStatus());
        assertEquals(NodeExecutionStatus.FAILED, run.getNodeExecution(nodeId).getStatus());
        assertEquals(1, historyRepository.eventBatches.size());
        List<ExecutionEvent> events = historyRepository.eventBatches.getFirst().events();
        assertTrue(events.stream().anyMatch(NodeFailedEvent.class::isInstance));
        assertEquals(1, wakeupPublisher.events.size());
        assertEquals("dispatch-failed", wakeupPublisher.events.getFirst().reason());
        assertEquals(1.0, runFailureCounter("critical_node_failed"));
        assertEquals(0.0, runFailureCounter("dispatch_failed"));
        assertTrue(run.getUncommittedEvents().isEmpty());
        assertEquals(initialVersion + events.size(), run.getVersion());
    }

    @Test
    void failNodeExecution_schedulesRetryWakeupWhenFailureIsRetryable() {
        RecordingRetryManager retryManager = new RecordingRetryManager();
        runManager.retryManager = retryManager;
        WorkflowRun run = WorkflowRun.create(TENANT, workflow(delayedRetryingNode()), Map.of());
        run.start();
        run.startNode(nodeId, 1);
        run.markEventsAsCommitted();
        long initialVersion = run.getVersion();
        runRepository.run = run;

        runManager.failNodeExecution(
                run.getId(),
                TENANT,
                nodeId,
                1,
                new ErrorInfo("TRANSIENT", "try again", "", Map.of()),
                "dispatch-failed").await().indefinitely();

        assertEquals(NodeExecutionStatus.RETRYING, run.getNodeExecution(nodeId).getStatus());
        assertEquals(2, run.getNodeExecution(nodeId).getAttempt());
        assertEquals(1, retryManager.schedules.size());
        RetrySchedule schedule = retryManager.schedules.getFirst();
        assertEquals(run.getId(), schedule.runId());
        assertEquals(TENANT, schedule.tenantId());
        assertEquals(nodeId, schedule.nodeId());
        assertEquals(2, schedule.attempt());
        assertTrue(schedule.delay().compareTo(Duration.ZERO) > 0);
        assertEquals(0.0, runFailureCounter("critical_node_failed"));
        assertEquals(0.0, runFailureCounter("dispatch_failed"));
        assertTrue(run.getUncommittedEvents().isEmpty());
        assertEquals(initialVersion + 1, run.getVersion());
    }

    @Test
    void handleNodeResult_completedOnce_persistsRunAndAppendsHistory() {
        WorkflowRun run = runningRun();
        runRepository.run = run;
        DefaultNodeExecutionResult result = new DefaultNodeExecutionResult(
                run.getId(),
                nodeId,
                1,
                NodeExecutionStatus.COMPLETED,
                Map.of("key", "value"),
                null,
                null);

        NodeResultHandlingOutcome outcome = runManager.handleNodeResultWithOutcome(run.getId(), result)
                .await().indefinitely();

        assertEquals(NodeExecutionResults.Acceptance.ACCEPT, outcome.acceptance());
        assertTrue(outcome.accepted());
        assertFalse(outcome.duplicate());
        assertTrue(outcome.runUpdated());
        assertTrue(outcome.historyAppended());
        assertTrue(outcome.processedMarkerWritten());
        assertFalse(outcome.retryWakeupScheduled());
        assertEquals(NodeExecutionStatus.COMPLETED, run.getNodeExecution(nodeId).getStatus());
        assertEquals(1, runRepository.updateCount.get());
        assertEquals(1, historyRepository.eventBatches.size());
        assertTrue(historyRepository.eventBatches.getFirst().events().stream()
                .anyMatch(NodeCompletedEvent.class::isInstance));
        assertTrue(run.getUncommittedEvents().isEmpty());
        assertEquals(ExecutionEventTypes.NODE_COMPLETED, historyRepository.appends.getFirst().type());
        assertEquals("ACCEPT", historyRepository.appends.getFirst().metadata().get("acceptance"));
        assertTrue(historyRepository.isNodeResultProcessed(run.getId(), nodeId, 1).await().indefinitely());
    }

    @Test
    void handleNodeResult_withTenantUsesTenantScopedRunLock() {
        WorkflowRun run = runningRun();
        runRepository.run = run;
        DefaultNodeExecutionResult result = new DefaultNodeExecutionResult(
                run.getId(),
                nodeId,
                1,
                NodeExecutionStatus.COMPLETED,
                Map.of("key", "value"),
                null,
                null);

        NodeResultHandlingOutcome outcome = runManager.handleNodeResultWithOutcome(run.getId(), TENANT, result)
                .await().indefinitely();

        assertEquals(TENANT, outcome.tenantId());
        assertEquals(TENANT, runRepository.lastLockTenant);
        assertEquals(NodeExecutionStatus.COMPLETED, run.getNodeExecution(nodeId).getStatus());
        assertTrue(historyRepository.isNodeResultProcessed(run.getId(), TENANT, nodeId, 1).await().indefinitely());
    }

    @Test
    void handleNodeResult_failureAppendsNodeFailedHistory() {
        WorkflowRun run = runningRun();
        runRepository.run = run;
        DefaultNodeExecutionResult result = new DefaultNodeExecutionResult(
                run.getId(),
                nodeId,
                1,
                NodeExecutionStatus.FAILED,
                Map.of(),
                new ErrorInfo("TEST_ERROR", "boom", "", Map.of()),
                null);

        runManager.handleNodeResult(run.getId(), result).await().indefinitely();

        assertEquals(NodeExecutionStatus.FAILED, run.getNodeExecution(nodeId).getStatus());
        assertEquals(1, historyRepository.eventBatches.size());
        assertTrue(historyRepository.eventBatches.getFirst().events().stream()
                .anyMatch(NodeFailedEvent.class::isInstance));
        assertTrue(run.getUncommittedEvents().isEmpty());
        assertEquals(ExecutionEventTypes.NODE_FAILED, historyRepository.appends.getFirst().type());
        assertEquals("TEST_ERROR", historyRepository.appends.getFirst().metadata().get("errorCode"));
        assertEquals("ACCEPT", historyRepository.appends.getFirst().metadata().get("acceptance"));
        assertTrue(historyRepository.isNodeResultProcessed(run.getId(), nodeId, 1).await().indefinitely());
    }

    @Test
    void handleNodeResult_retryingFailureSchedulesDelayedRetryWakeup() {
        RecordingRetryManager retryManager = new RecordingRetryManager();
        runManager.retryManager = retryManager;
        WorkflowRun run = WorkflowRun.create(TENANT, workflow(delayedRetryingNode()), Map.of());
        run.start();
        run.startNode(nodeId, 1);
        runRepository.run = run;
        DefaultNodeExecutionResult result = new DefaultNodeExecutionResult(
                run.getId(),
                nodeId,
                1,
                NodeExecutionStatus.FAILED,
                Map.of(),
                new ErrorInfo("TRANSIENT", "try again", "", Map.of()),
                null);

        runManager.handleNodeResult(run.getId(), result).await().indefinitely();

        assertEquals(NodeExecutionStatus.RETRYING, run.getNodeExecution(nodeId).getStatus());
        assertEquals(2, run.getNodeExecution(nodeId).getAttempt());
        assertEquals(1, retryManager.schedules.size());
        RetrySchedule schedule = retryManager.schedules.get(0);
        assertEquals(run.getId(), schedule.runId());
        assertEquals(TENANT, schedule.tenantId());
        assertEquals(nodeId, schedule.nodeId());
        assertEquals(2, schedule.attempt());
        assertTrue(schedule.delay().compareTo(Duration.ZERO) > 0);
    }

    @Test
    void handleNodeResult_whenRunUpdateFails_doesNotMarkResultProcessed() {
        WorkflowRun run = runningRun();
        runRepository.run = run;
        runRepository.updateFailure = new IllegalStateException("storage unavailable");
        DefaultNodeExecutionResult result = new DefaultNodeExecutionResult(
                run.getId(),
                nodeId,
                1,
                NodeExecutionStatus.COMPLETED,
                Map.of("key", "value"),
                null,
                null);

        assertThrows(IllegalStateException.class,
                () -> runManager.handleNodeResult(run.getId(), result).await().indefinitely());

        assertEquals(1, runRepository.updateCount.get());
        assertTrue(historyRepository.appends.isEmpty());
        assertFalse(historyRepository.isNodeResultProcessed(run.getId(), nodeId, 1).await().indefinitely());
    }

    @Test
    void handleNodeResult_whenAlreadyAppliedButUnmarked_recordsProcessedMarkerWithoutUpdatingRun() {
        WorkflowRun run = runningRun();
        run.completeNode(nodeId, 1, Map.of("key", "value"));
        runRepository.run = run;
        DefaultNodeExecutionResult result = new DefaultNodeExecutionResult(
                run.getId(),
                nodeId,
                1,
                NodeExecutionStatus.COMPLETED,
                Map.of("key", "value"),
                null,
                null);

        runManager.handleNodeResult(run.getId(), result).await().indefinitely();

        assertEquals(0, runRepository.updateCount.get());
        assertEquals(ExecutionEventTypes.NODE_COMPLETED, historyRepository.appends.getFirst().type());
        assertEquals("ALREADY_APPLIED", historyRepository.appends.getFirst().metadata().get("acceptance"));
        assertTrue(historyRepository.isNodeResultProcessed(run.getId(), nodeId, 1).await().indefinitely());
    }

    @Test
    void handleNodeResult_staleAttempt_appendsIgnoredHistoryAndMarksProcessed() {
        WorkflowRun run = WorkflowRun.create(TENANT, workflow(retryingNode()), Map.of());
        run.start();
        run.startNode(nodeId, 1);
        run.failNode(nodeId, 1, new ErrorInfo("TRANSIENT", "try again", "", Map.of()));
        runRepository.run = run;
        DefaultNodeExecutionResult staleResult = new DefaultNodeExecutionResult(
                run.getId(),
                nodeId,
                1,
                NodeExecutionStatus.COMPLETED,
                Map.of("key", "late"),
                null,
                null);

        NodeResultHandlingOutcome outcome = runManager.handleNodeResultWithOutcome(run.getId(), staleResult)
                .await().indefinitely();

        assertEquals(NodeExecutionResults.Acceptance.STALE, outcome.acceptance());
        assertFalse(outcome.runUpdated());
        assertTrue(outcome.historyAppended());
        assertTrue(outcome.processedMarkerWritten());
        assertEquals(NodeExecutionStatus.RETRYING, run.getNodeExecution(nodeId).getStatus());
        assertEquals(2, run.getNodeExecution(nodeId).getAttempt());
        assertEquals(0, runRepository.updateCount.get());
        assertEquals(ExecutionEventTypes.NODE_RESULT_IGNORED, historyRepository.appends.getFirst().type());
        assertEquals("STALE", historyRepository.appends.getFirst().metadata().get("acceptance"));
        assertEquals("Stale node result attempt", historyRepository.appends.getFirst().metadata().get("reason"));
        assertTrue(historyRepository.isNodeResultProcessed(run.getId(), nodeId, 1).await().indefinitely());
    }

    @Test
    void handleNodeResult_futureAttemptRejectedWithoutMutation() {
        WorkflowRun run = runningRun();
        runRepository.run = run;
        DefaultNodeExecutionResult result = new DefaultNodeExecutionResult(
                run.getId(),
                nodeId,
                2,
                NodeExecutionStatus.COMPLETED,
                Map.of("key", "future"),
                null,
                null);

        GamelanException error = assertThrows(GamelanException.class,
                () -> runManager.handleNodeResult(run.getId(), result).await().indefinitely());

        assertEquals(ErrorCode.TASK_VALIDATION_FAILED, error.getErrorCode());
        assertEquals("Future node result attempt: expected 1 but got 2", error.getSafeMessage());
        assertEquals(NodeExecutionStatus.RUNNING, run.getNodeExecution(nodeId).getStatus());
        assertEquals(0, runRepository.updateCount.get());
        assertTrue(historyRepository.appends.isEmpty());
        assertFalse(historyRepository.isNodeResultProcessed(run.getId(), nodeId, 2).await().indefinitely());
    }

    @Test
    void handleNodeResult_rejectsRunIdMismatchBeforeMutation() {
        WorkflowRun run = runningRun();
        runRepository.run = run;
        DefaultNodeExecutionResult result = new DefaultNodeExecutionResult(
                WorkflowRunId.of("other-run"),
                nodeId,
                1,
                NodeExecutionStatus.COMPLETED,
                Map.of("key", "wrong-run"),
                null,
                null);

        GamelanException error = assertThrows(GamelanException.class,
                () -> runManager.handleNodeResult(run.getId(), result).await().indefinitely());

        assertEquals(ErrorCode.TASK_VALIDATION_FAILED, error.getErrorCode());
        assertEquals(
                "NodeExecutionResult runId mismatch: expected " + run.getId().value() + " but got other-run",
                error.getSafeMessage());
        assertEquals(NodeExecutionStatus.RUNNING, run.getNodeExecution(nodeId).getStatus());
        assertEquals(0, runRepository.updateCount.get());
        assertTrue(historyRepository.appends.isEmpty());
        assertFalse(historyRepository.isNodeResultProcessed(run.getId(), nodeId, 1).await().indefinitely());
    }

    @Test
    void handleNodeResult_conflictingTerminalDuplicateRejectedWithoutHistoryAppend() {
        WorkflowRun run = runningRun();
        run.completeNode(nodeId, 1, Map.of("key", "value"));
        runRepository.run = run;
        DefaultNodeExecutionResult result = new DefaultNodeExecutionResult(
                run.getId(),
                nodeId,
                1,
                NodeExecutionStatus.FAILED,
                Map.of(),
                new ErrorInfo("TEST_ERROR", "boom", "", Map.of()),
                null);

        GamelanException error = assertThrows(GamelanException.class,
                () -> runManager.handleNodeResult(run.getId(), result).await().indefinitely());

        assertEquals(ErrorCode.TASK_VALIDATION_FAILED, error.getErrorCode());
        assertEquals(
                "Conflicting terminal node result: existing COMPLETED but got FAILED",
                error.getSafeMessage());
        assertEquals(NodeExecutionStatus.COMPLETED, run.getNodeExecution(nodeId).getStatus());
        assertEquals(0, runRepository.updateCount.get());
        assertTrue(historyRepository.appends.isEmpty());
        assertFalse(historyRepository.isNodeResultProcessed(run.getId(), nodeId, 1).await().indefinitely());
    }

    @Test
    void handleNodeResult_ignoresAlreadyProcessedResult() {
        WorkflowRun run = runningRun();
        runRepository.run = run;
        historyRepository.nodeResultProcessed = true;
        DefaultNodeExecutionResult result = new DefaultNodeExecutionResult(
                run.getId(),
                nodeId,
                1,
                NodeExecutionStatus.COMPLETED,
                Map.of(),
                null,
                null);

        NodeResultHandlingOutcome outcome = runManager.handleNodeResultWithOutcome(run.getId(), result)
                .await().indefinitely();

        assertEquals(NodeExecutionResults.Acceptance.ALREADY_PROCESSED, outcome.acceptance());
        assertTrue(outcome.duplicate());
        assertFalse(outcome.runUpdated());
        assertFalse(outcome.historyAppended());
        assertFalse(outcome.processedMarkerWritten());
        assertEquals(NodeExecutionStatus.RUNNING, run.getNodeExecution(nodeId).getStatus());
        assertEquals(0, runRepository.updateCount.get());
        assertTrue(historyRepository.appends.isEmpty());
    }

    @Test
    void handleNodeResult_afterTerminalCancellationRecordsIgnoredHistoryWithoutMutatingRun() {
        WorkflowRun run = runningRun();
        run.cancel("operator stop");
        runRepository.run = run;
        DefaultNodeExecutionResult result = new DefaultNodeExecutionResult(
                run.getId(),
                nodeId,
                1,
                NodeExecutionStatus.COMPLETED,
                Map.of("key", "late"),
                null,
                null);

        NodeResultHandlingOutcome outcome = runManager.handleNodeResultWithOutcome(run.getId(), result)
                .await().indefinitely();

        assertEquals(NodeExecutionResults.Acceptance.RUN_NOT_ACCEPTING_RESULTS, outcome.acceptance());
        assertFalse(outcome.accepted());
        assertFalse(outcome.duplicate());
        assertTrue(outcome.ignored());
        assertFalse(outcome.runUpdated());
        assertTrue(outcome.historyAppended());
        assertTrue(outcome.processedMarkerWritten());
        assertFalse(outcome.retryWakeupScheduled());
        assertEquals(RunStatus.CANCELLED, run.getStatus());
        assertEquals(NodeExecutionStatus.RUNNING, run.getNodeExecution(nodeId).getStatus());
        assertEquals(0, runRepository.updateCount.get());
        assertEquals(ExecutionEventTypes.NODE_RESULT_IGNORED, historyRepository.appends.getFirst().type());
        assertEquals("RUN_NOT_ACCEPTING_RESULTS", historyRepository.appends.getFirst().metadata().get("acceptance"));
        assertEquals("Run is not accepting node results: CANCELLED",
                historyRepository.appends.getFirst().metadata().get("reason"));
        assertTrue(historyRepository.isNodeResultProcessed(run.getId(), nodeId, 1).await().indefinitely());
    }

    @Test
    void handleNodeResult_duringCompensationRecordsIgnoredHistoryWithoutMutatingRun() {
        WorkflowRun run = WorkflowRun.create(TENANT, workflowWithCompensation(), Map.of());
        run.start();
        run.startNode(nodeId, 1);
        run.cancel("operator stop");
        runRepository.run = run;
        DefaultNodeExecutionResult result = new DefaultNodeExecutionResult(
                run.getId(),
                nodeId,
                1,
                NodeExecutionStatus.COMPLETED,
                Map.of("key", "late"),
                null,
                null);

        NodeResultHandlingOutcome outcome = runManager.handleNodeResultWithOutcome(run.getId(), result)
                .await().indefinitely();

        assertEquals(NodeExecutionResults.Acceptance.RUN_NOT_ACCEPTING_RESULTS, outcome.acceptance());
        assertTrue(outcome.ignored());
        assertFalse(outcome.runUpdated());
        assertTrue(outcome.historyAppended());
        assertTrue(outcome.processedMarkerWritten());
        assertEquals(RunStatus.COMPENSATING, run.getStatus());
        assertEquals(NodeExecutionStatus.RUNNING, run.getNodeExecution(nodeId).getStatus());
        assertEquals(0, runRepository.updateCount.get());
        assertEquals("RUN_NOT_ACCEPTING_RESULTS", historyRepository.appends.getFirst().metadata().get("acceptance"));
        assertEquals("Run is not accepting node results: COMPENSATING",
                historyRepository.appends.getFirst().metadata().get("reason"));
        assertTrue(historyRepository.isNodeResultProcessed(run.getId(), nodeId, 1).await().indefinitely());
    }

    @Test
    void createExecutionToken_storesTokenForValidation() {
        ExecutionToken token = runManager.createExecutionToken(WorkflowRunId.of("run-1"), nodeId, 1)
                .await().indefinitely();

        assertTrue(runRepository.validateToken(token).await().indefinitely());
        assertTrue(runRepository.tokens.containsKey(ExecutionTokenHash.sha256(token.value())));
    }

    @Test
    void createExecutionToken_withTenantStoresTenantBoundToken() {
        WorkflowRun run = WorkflowRun.create(TENANT, workflow(), Map.of());
        runRepository.run = run;

        ExecutionToken token = runManager.createExecutionToken(run.getId(), TENANT, nodeId, 1)
                .await().indefinitely();
        ExecutionToken crossTenantReplay = new ExecutionToken(
                token.value(),
                run.getId(),
                TenantId.of("other-tenant"),
                nodeId,
                1,
                token.expiresAt());

        assertEquals(TENANT, token.tenantId());
        assertTrue(runRepository.validateToken(token).await().indefinitely());
        assertFalse(runRepository.validateToken(crossTenantReplay).await().indefinitely());
    }

    @Test
    void createExecutionToken_withTenantRejectsTenantMismatchBeforeIssuing() {
        WorkflowRun run = WorkflowRun.create(TENANT, workflow(), Map.of());
        runRepository.run = run;
        TenantId otherTenant = TenantId.of("other-tenant");

        assertThrows(NoSuchElementException.class,
                () -> runManager.createExecutionToken(run.getId(), otherTenant, nodeId, 1).await().indefinitely());

        assertTrue(runRepository.tokens.isEmpty());
    }

    @Test
    void registerCallback_withTenantRejectsTenantMismatchBeforeStoring() {
        WorkflowRun run = WorkflowRun.create(TENANT, workflow(), Map.of());
        runRepository.run = run;
        TenantId otherTenant = TenantId.of("other-tenant");

        assertThrows(NoSuchElementException.class,
                () -> runManager.registerCallback(
                        run.getId(),
                        otherTenant,
                        nodeId,
                        CallbackConfig.webhook("https://example.test/callback")).await().indefinitely());

        assertTrue(runRepository.callbacks.isEmpty());
    }

    @Test
    void registerCallback_withTenantStoresTenantBoundCallback() {
        WorkflowRun run = WorkflowRun.create(TENANT, workflow(), Map.of());
        runRepository.run = run;

        CallbackRegistration registration = runManager.registerCallback(
                run.getId(),
                TENANT,
                nodeId,
                CallbackConfig.webhook("https://example.test/callback")).await().indefinitely();

        assertEquals(TENANT, registration.tenantId());
        assertTrue(runRepository.validateCallback(run.getId(), TENANT, registration.callbackToken())
                .await().indefinitely());
        assertFalse(runRepository.validateCallback(run.getId(), TenantId.of("other-tenant"), registration.callbackToken())
                .await().indefinitely());
        assertTrue(runRepository.validateCallback(run.getId(), registration.callbackToken()).await().indefinitely());
    }

    @Test
    void startRun_rejectsTenantMismatchBeforeMutation() {
        WorkflowRun run = WorkflowRun.create(TENANT, workflow(), Map.of());
        runRepository.run = run;
        TenantId otherTenant = TenantId.of("other-tenant");

        assertThrows(NoSuchElementException.class,
                () -> runManager.startRun(run.getId(), otherTenant).await().indefinitely());

        assertEquals(RunStatus.CREATED, run.getStatus());
        assertEquals(0, runRepository.updateCount.get());
        assertTrue(historyRepository.appends.isEmpty());
    }

    @Test
    void startRun_whenAlreadyRunningWakesRunWithoutMutation() throws Exception {
        WorkflowRun run = WorkflowRun.create(TENANT, workflow(), Map.of());
        run.start();
        runRepository.run = run;
        CountDownLatch updatePublished = new CountDownLatch(1);
        final JsonObject[] payload = new JsonObject[1];
        vertx.eventBus().<Object>consumer(WorkflowRunUpdateEvent.ADDRESS)
                .handler(message -> {
                    if (message.body() instanceof JsonObject json) {
                        payload[0] = json;
                        updatePublished.countDown();
                    }
                });

        WorkflowRun returned = runManager.startRun(run.getId(), TENANT).await().indefinitely();

        assertEquals(run, returned);
        assertEquals(RunStatus.RUNNING, run.getStatus());
        assertEquals(0, runRepository.updateCount.get());
        assertTrue(historyRepository.appends.isEmpty());
        assertTrue(updatePublished.await(2, TimeUnit.SECONDS));
        assertEquals(run.getId().value(), payload[0].getString("runId"));
        assertEquals(TENANT.value(), payload[0].getString("tenantId"));
        assertEquals("run-start-already-active", payload[0].getString("reason"));
    }

    @Test
    void startRun_whenPendingSchedulesStartNodesAndPublishesRunUpdate() throws Exception {
        WorkflowDefinition definition = workflow();
        WorkflowRun run = WorkflowRun.restore(
                new WorkflowRunSnapshot(
                        WorkflowRunId.generate(),
                        TENANT,
                        definition.id(),
                        definition.version(),
                        RunStatus.PENDING,
                        Map.of(),
                        Map.of(),
                        List.of(),
                        null,
                        Map.of(),
                        null,
                        Instant.now(),
                        Instant.now(),
                        null,
                        1),
                definition);
        runRepository.run = run;
        RunUpdateCapture update = captureRunUpdates();

        WorkflowRun returned = runManager.startRun(run.getId(), TENANT).await().indefinitely();

        assertEquals(run, returned);
        assertEquals(RunStatus.RUNNING, run.getStatus());
        assertEquals(List.of(nodeId), run.getPendingNodes());
        assertEquals(1, runRepository.updateCount.get());
        assertEventBatch(NodeScheduledEvent.class);
        assertTrue(run.getUncommittedEvents().isEmpty());
        HistoryAppend append = historyRepository.appends.getFirst();
        assertEquals(ExecutionEventTypes.STATUS_CHANGED, append.type());
        assertEquals(RunStatus.RUNNING.name(), append.message());
        assertTrue(update.await());
        assertEquals("run-started", update.message().getString("reason"));
    }

    @Test
    void startRun_whenLegacyDefinitionHasNoStartNodesRejectsWithoutMutation() {
        WorkflowDefinition definition = legacyWorkflowWithoutNodes();
        WorkflowRun run = WorkflowRun.restore(
                new WorkflowRunSnapshot(
                        WorkflowRunId.generate(),
                        TENANT,
                        definition.id(),
                        definition.version(),
                        RunStatus.CREATED,
                        Map.of(),
                        Map.of(),
                        List.of(),
                        null,
                        Map.of(),
                        null,
                        Instant.now(),
                        null,
                        null,
                        1),
                definition);
        runRepository.run = run;

        GamelanException error = assertThrows(GamelanException.class,
                () -> runManager.startRun(run.getId(), TENANT).await().indefinitely());

        assertEquals(ErrorCode.WORKFLOW_INVALID_DEFINITION, error.getErrorCode());
        assertTrue(error.getSafeMessage().contains("Workflow must have at least one node"));
        assertEquals(RunStatus.CREATED, run.getStatus());
        assertTrue(run.getPendingNodes().isEmpty());
        assertEquals(0, runRepository.updateCount.get());
        assertTrue(historyRepository.eventBatches.isEmpty());
        assertTrue(run.getUncommittedEvents().isEmpty());
        assertTrue(historyRepository.appends.isEmpty());
    }

    @Test
    void startRun_usesInjectedWakeupPublisherWithoutEventBusCoupling() {
        WorkflowRun run = WorkflowRun.create(TENANT, workflow(), Map.of());
        run.markEventsAsCommitted();
        long initialVersion = run.getVersion();
        runRepository.run = run;
        RecordingWakeupPublisher wakeupPublisher = new RecordingWakeupPublisher();
        runManager.wakeupPublisher = wakeupPublisher;
        runManager.eventBus = null;

        runManager.startRun(run.getId(), TENANT).await().indefinitely();

        assertEquals(RunStatus.RUNNING, run.getStatus());
        assertEquals(1, runRepository.updateCount.get());
        assertEquals(1, historyRepository.eventBatches.size());
        List<ExecutionEvent> events = historyRepository.eventBatches.getFirst().events();
        assertEquals(1, events.size());
        assertTrue(events.getFirst() instanceof NodeScheduledEvent);
        assertTrue(run.getUncommittedEvents().isEmpty());
        assertEquals(initialVersion + events.size(), run.getVersion());
        assertEquals(1, wakeupPublisher.events.size());
        WorkflowRunUpdateEvent event = wakeupPublisher.events.getFirst();
        assertEquals(run.getId().value(), event.runId());
        assertEquals(TENANT.value(), event.tenantId());
        assertEquals("run-started", event.reason());
    }

    @Test
    void suspendRun_acceptsMissingWaitingNodeAndPublishesRunUpdate() throws Exception {
        WorkflowRun run = runningRun();
        runRepository.run = run;
        RunUpdateCapture update = captureRunUpdates();

        WorkflowRun returned = runManager.suspendRun(run.getId(), TENANT, null, null).await().indefinitely();

        assertEquals(run, returned);
        assertEquals(RunStatus.SUSPENDED, run.getStatus());
        assertEquals(1, runRepository.updateCount.get());
        assertEventBatch(WorkflowSuspendedEvent.class);
        assertTrue(run.getUncommittedEvents().isEmpty());
        HistoryAppend append = historyRepository.appends.getFirst();
        assertEquals(ExecutionEventTypes.STATUS_CHANGED, append.type());
        assertEquals(RunStatus.SUSPENDED.name(), append.message());
        assertEquals("", append.metadata().get("reason"));
        assertEquals("", append.metadata().get("waitingOnNode"));
        assertTrue(update.await());
        assertEquals(run.getId().value(), update.message().getString("runId"));
        assertEquals(TENANT.value(), update.message().getString("tenantId"));
        assertEquals("run-suspended", update.message().getString("reason"));
    }

    @Test
    void suspendRun_whenAlreadySuspendedWakesRunWithoutMutation() throws Exception {
        WorkflowRun run = suspendedRun();
        runRepository.run = run;
        RunUpdateCapture update = captureRunUpdates();

        WorkflowRun returned = runManager.suspendRun(run.getId(), TENANT, "waiting again", nodeId)
                .await().indefinitely();

        assertEquals(run, returned);
        assertEquals(RunStatus.SUSPENDED, run.getStatus());
        assertEquals(0, runRepository.updateCount.get());
        assertTrue(historyRepository.appends.isEmpty());
        assertTrue(update.await());
        assertEquals(run.getId().value(), update.message().getString("runId"));
        assertEquals(TENANT.value(), update.message().getString("tenantId"));
        assertEquals("run-suspend-already-suspended", update.message().getString("reason"));
    }

    @Test
    void cancelRun_allowsNullReasonAndPublishesRunUpdate() throws Exception {
        WorkflowRun run = runningRun();
        runRepository.run = run;
        RunUpdateCapture update = captureRunUpdates();

        runManager.cancelRun(run.getId(), TENANT, null).await().indefinitely();

        assertEquals(RunStatus.CANCELLED, run.getStatus());
        assertEquals(1, runRepository.updateCount.get());
        assertEventBatch(WorkflowCancelledEvent.class);
        assertTrue(run.getUncommittedEvents().isEmpty());
        HistoryAppend append = historyRepository.appends.getFirst();
        assertEquals(ExecutionEventTypes.STATUS_CHANGED, append.type());
        assertEquals(RunStatus.CANCELLED.name(), append.message());
        assertEquals("", append.metadata().get("reason"));
        assertTrue(update.await());
        assertEquals(run.getId().value(), update.message().getString("runId"));
        assertEquals(TENANT.value(), update.message().getString("tenantId"));
        assertEquals("run-cancelled", update.message().getString("reason"));
    }

    @Test
    void cancelRun_whenAlreadyCancelledWakesRunWithoutMutation() throws Exception {
        WorkflowRun run = runningRun();
        run.cancel("stop");
        runRepository.run = run;
        RunUpdateCapture update = captureRunUpdates();

        runManager.cancelRun(run.getId(), TENANT, "stop").await().indefinitely();

        assertEquals(RunStatus.CANCELLED, run.getStatus());
        assertEquals(0, runRepository.updateCount.get());
        assertTrue(historyRepository.appends.isEmpty());
        assertTrue(update.await());
        assertEquals(run.getId().value(), update.message().getString("runId"));
        assertEquals(TENANT.value(), update.message().getString("tenantId"));
        assertEquals("run-cancel-already-terminal", update.message().getString("reason"));
    }

    @Test
    void cancelRun_whenCompensationStartsRecordsCompensationAuditAndWakeup() throws Exception {
        WorkflowRun run = WorkflowRun.create(TENANT, workflowWithCompensationChain(), Map.of());
        run.start();
        run.startNode(nodeId, 1);
        run.completeNode(nodeId, 1, Map.of("result", "ok"));
        run.markEventsAsCommitted();
        runRepository.run = run;
        RunUpdateCapture update = captureRunUpdates();

        runManager.cancelRun(run.getId(), TENANT, "operator stop").await().indefinitely();

        assertEquals(RunStatus.COMPENSATING, run.getStatus());
        assertNull(run.getCompletedAt());
        assertEquals(1, runRepository.updateCount.get());
        assertEventBatch(WorkflowCancelledEvent.class, CompensationStartedEvent.class);
        assertTrue(run.getUncommittedEvents().isEmpty());
        assertEquals(2, historyRepository.appends.size());
        assertEquals(ExecutionEventTypes.STATUS_CHANGED, historyRepository.appends.get(0).type());
        assertEquals(RunStatus.CANCELLED.name(), historyRepository.appends.get(0).message());
        HistoryAppend compensationStarted = historyRepository.appends.get(1);
        assertEquals(ExecutionEventTypes.COMPENSATION_STARTED, compensationStarted.type());
        assertEquals(RunStatus.COMPENSATING.name(), compensationStarted.message());
        assertEquals(List.of("node-1"), compensationStarted.metadata().get("nodesToCompensate"));
        assertEquals(1, compensationStarted.metadata().get("nodesToCompensateCount"));
        assertTrue(update.await());
        assertEquals("run-compensating", update.message().getString("reason"));
    }

    @Test
    void cancelRun_whenCompensatingRejectsWithoutMutation() {
        WorkflowRun run = WorkflowRun.create(TENANT, workflowWithCompensation(), Map.of());
        run.start();
        run.startNode(nodeId, 1);
        run.cancel("operator stop");
        runRepository.run = run;

        GamelanException error = assertThrows(GamelanException.class,
                () -> runManager.cancelRun(run.getId(), TENANT, "second stop").await().indefinitely());

        assertEquals(ErrorCode.RUN_INVALID_STATE, error.getErrorCode());
        assertEquals("Invalid state transition from COMPENSATING to CANCELLED", error.getSafeMessage());
        assertEquals(RunStatus.COMPENSATING, run.getStatus());
        assertEquals(0, runRepository.updateCount.get());
        assertTrue(historyRepository.appends.isEmpty());
    }

    @Test
    void completeCompensation_whenNotCompensatingRejectsWithoutMutation() {
        WorkflowRun run = runningRun();
        runRepository.run = run;

        GamelanException error = assertThrows(GamelanException.class,
                () -> runManager.completeCompensation(run.getId(), TENANT).await().indefinitely());

        assertEquals(ErrorCode.RUN_INVALID_STATE, error.getErrorCode());
        assertEquals("Invalid state transition from RUNNING to COMPENSATED", error.getSafeMessage());
        assertEquals(RunStatus.RUNNING, run.getStatus());
        assertEquals(0, runRepository.updateCount.get());
        assertTrue(historyRepository.appends.isEmpty());
    }

    @Test
    void completeCompensation_recordsCompensationCompletedAuditAndWakeup() throws Exception {
        WorkflowRun run = WorkflowRun.create(TENANT, workflowWithCompensationChain(), Map.of());
        run.start();
        run.startNode(nodeId, 1);
        run.completeNode(nodeId, 1, Map.of("result", "ok"));
        run.cancel("operator stop");
        run.markEventsAsCommitted();
        runRepository.run = run;
        RunUpdateCapture update = captureRunUpdates();

        runManager.completeCompensation(run.getId(), TENANT).await().indefinitely();

        assertEquals(RunStatus.COMPENSATED, run.getStatus());
        assertTrue(run.getCompensationState().isComplete());
        assertEquals(1, runRepository.updateCount.get());
        assertEventBatch(CompensationCompletedEvent.class);
        assertTrue(run.getUncommittedEvents().isEmpty());
        HistoryAppend append = historyRepository.appends.getFirst();
        assertEquals(ExecutionEventTypes.COMPENSATION_COMPLETED, append.type());
        assertEquals(RunStatus.COMPENSATED.name(), append.message());
        assertEquals(RunStatus.COMPENSATED.name(), append.metadata().get("status"));
        assertEquals("COMPLETED", append.metadata().get("compensationStatus"));
        assertEquals(List.of(), append.metadata().get("nodesToCompensate"));
        assertEquals(0, append.metadata().get("nodesToCompensateCount"));
        assertEquals(List.of("node-1"), append.metadata().get("compensatedNodes"));
        assertEquals(1, append.metadata().get("compensatedNodesCount"));
        assertTrue(update.await());
        assertEquals("compensation-completed", update.message().getString("reason"));
    }

    @Test
    void completeCompensation_whenAlreadyCompensatedWakesRunWithoutMutation() throws Exception {
        WorkflowRun run = WorkflowRun.create(TENANT, workflowWithCompensationChain(), Map.of());
        run.start();
        run.startNode(nodeId, 1);
        run.completeNode(nodeId, 1, Map.of("result", "ok"));
        run.cancel("operator stop");
        run.completeCompensation();
        run.markEventsAsCommitted();
        runRepository.run = run;
        RunUpdateCapture update = captureRunUpdates();

        runManager.completeCompensation(run.getId(), TENANT).await().indefinitely();

        assertEquals(RunStatus.COMPENSATED, run.getStatus());
        assertTrue(run.getCompensationState().isComplete());
        assertEquals(0, runRepository.updateCount.get());
        assertTrue(historyRepository.eventBatches.isEmpty());
        assertTrue(historyRepository.appends.isEmpty());
        assertTrue(update.await());
        assertEquals("compensation-complete-already-terminal", update.message().getString("reason"));
    }

    @Test
    void failCompensation_acceptsNullErrorAndRecordsCompensationFailureAudit() throws Exception {
        WorkflowRun run = WorkflowRun.create(TENANT, workflowWithCompensationChain(), Map.of());
        run.start();
        run.startNode(nodeId, 1);
        run.completeNode(nodeId, 1, Map.of("result", "ok"));
        run.cancel("operator stop");
        run.markEventsAsCommitted();
        runRepository.run = run;
        RunUpdateCapture update = captureRunUpdates();

        runManager.failCompensation(run.getId(), TENANT, null).await().indefinitely();

        assertEquals(RunStatus.FAILED, run.getStatus());
        assertTrue(run.getCompensationState().isFailed());
        assertEquals(1, runRepository.updateCount.get());
        assertEventBatch(CompensationFailedEvent.class);
        assertTrue(run.getUncommittedEvents().isEmpty());
        HistoryAppend append = historyRepository.appends.getFirst();
        assertEquals(ExecutionEventTypes.COMPENSATION_FAILED, append.type());
        assertEquals("Compensation failed", append.message());
        assertEquals(RunStatus.FAILED.name(), append.metadata().get("status"));
        assertEquals("FAILED", append.metadata().get("compensationStatus"));
        assertEquals(CompensationErrors.COMPENSATION_FAILED, append.metadata().get("errorCode"));
        assertEquals(List.of("node-1"), append.metadata().get("nodesToCompensate"));
        assertEquals(1, append.metadata().get("nodesToCompensateCount"));
        assertEquals(List.of(), append.metadata().get("compensatedNodes"));
        assertEquals(0, append.metadata().get("compensatedNodesCount"));
        assertEquals(1.0, runFailureCounter("compensation_failed"));
        assertTrue(update.await());
        assertEquals("compensation-failed", update.message().getString("reason"));
    }

    @Test
    void failCompensation_whenAlreadyFailedWakesRunWithoutMutation() throws Exception {
        WorkflowRun run = WorkflowRun.create(TENANT, workflowWithCompensationChain(), Map.of());
        run.start();
        run.startNode(nodeId, 1);
        run.completeNode(nodeId, 1, Map.of("result", "ok"));
        run.cancel("operator stop");
        run.failCompensation(CompensationErrors.failed());
        run.markEventsAsCommitted();
        runRepository.run = run;
        RunUpdateCapture update = captureRunUpdates();

        runManager.failCompensation(
                run.getId(),
                TENANT,
                new ErrorInfo("LATE_FAILURE", "duplicate failure", "", Map.of())).await().indefinitely();

        assertEquals(RunStatus.FAILED, run.getStatus());
        assertTrue(run.getCompensationState().isFailed());
        assertEquals(0, runRepository.updateCount.get());
        assertTrue(historyRepository.eventBatches.isEmpty());
        assertTrue(historyRepository.appends.isEmpty());
        assertEquals(0.0, runFailureCounter("compensation_failed"));
        assertTrue(update.await());
        assertEquals("compensation-fail-already-terminal", update.message().getString("reason"));
    }

    @Test
    void completeRun_acceptsNullOutputsAndPublishesRunUpdate() throws Exception {
        WorkflowRun run = runningRun();
        runRepository.run = run;
        RunUpdateCapture update = captureRunUpdates();

        WorkflowRun returned = runManager.completeRun(run.getId(), TENANT, null).await().indefinitely();

        assertEquals(run, returned);
        assertEquals(RunStatus.COMPLETED, run.getStatus());
        assertEquals(1, runRepository.updateCount.get());
        assertEventBatch(WorkflowCompletedEvent.class);
        assertTrue(run.getUncommittedEvents().isEmpty());
        HistoryAppend append = historyRepository.appends.getFirst();
        assertEquals(ExecutionEventTypes.RUN_COMPLETED, append.type());
        assertEquals("Run completed", append.message());
        assertTrue(append.metadata().isEmpty());
        assertTrue(update.await());
        assertEquals(run.getId().value(), update.message().getString("runId"));
        assertEquals(TENANT.value(), update.message().getString("tenantId"));
        assertEquals("run-completed", update.message().getString("reason"));
    }

    @Test
    void completeRun_whenAlreadyCompletedWakesRunWithoutMutation() throws Exception {
        WorkflowRun run = runningRun();
        run.complete(Map.of("result", "ok"));
        runRepository.run = run;
        RunUpdateCapture update = captureRunUpdates();

        WorkflowRun returned = runManager.completeRun(run.getId(), TENANT, Map.of("result", "ok"))
                .await().indefinitely();

        assertEquals(run, returned);
        assertEquals(RunStatus.COMPLETED, run.getStatus());
        assertEquals(0, runRepository.updateCount.get());
        assertTrue(historyRepository.appends.isEmpty());
        assertTrue(update.await());
        assertEquals(run.getId().value(), update.message().getString("runId"));
        assertEquals(TENANT.value(), update.message().getString("tenantId"));
        assertEquals("run-complete-already-terminal", update.message().getString("reason"));
    }

    @Test
    void failRun_acceptsNullErrorAndPublishesRunUpdate() throws Exception {
        WorkflowRun run = runningRun();
        runRepository.run = run;
        RunUpdateCapture update = captureRunUpdates();

        WorkflowRun returned = runManager.failRun(run.getId(), TENANT, null).await().indefinitely();

        assertEquals(run, returned);
        assertEquals(RunStatus.FAILED, run.getStatus());
        assertEquals(1, runRepository.updateCount.get());
        assertEventBatch(WorkflowFailedEvent.class);
        assertTrue(run.getUncommittedEvents().isEmpty());
        HistoryAppend append = historyRepository.appends.getFirst();
        assertEquals(ExecutionEventTypes.RUN_FAILED, append.type());
        assertEquals("Workflow failed", append.message());
        assertEquals("WORKFLOW_FAILED", append.metadata().get("errorCode"));
        assertEquals(1.0, runFailureCounter("workflow_failed"));
        assertTrue(update.await());
        assertEquals(run.getId().value(), update.message().getString("runId"));
        assertEquals(TENANT.value(), update.message().getString("tenantId"));
        assertEquals("run-failed", update.message().getString("reason"));
    }

    @Test
    void failRun_whenCompensationStartsRecordsCompensationAuditAndWakeup() throws Exception {
        WorkflowRun run = WorkflowRun.create(TENANT, workflowWithCompensationChain(), Map.of());
        run.start();
        run.startNode(nodeId, 1);
        run.completeNode(nodeId, 1, Map.of("result", "ok"));
        run.markEventsAsCommitted();
        runRepository.run = run;
        RunUpdateCapture update = captureRunUpdates();

        WorkflowRun returned = runManager.failRun(
                run.getId(),
                TENANT,
                new ErrorInfo("TEST_FAILURE", "boom", "", Map.of())).await().indefinitely();

        assertEquals(run, returned);
        assertEquals(RunStatus.COMPENSATING, run.getStatus());
        assertNull(run.getCompletedAt());
        assertEquals(1, runRepository.updateCount.get());
        assertEventBatch(WorkflowFailedEvent.class, CompensationStartedEvent.class);
        assertTrue(run.getUncommittedEvents().isEmpty());
        assertEquals(2, historyRepository.appends.size());
        assertEquals(ExecutionEventTypes.RUN_FAILED, historyRepository.appends.get(0).type());
        assertEquals("boom", historyRepository.appends.get(0).message());
        HistoryAppend compensationStarted = historyRepository.appends.get(1);
        assertEquals(ExecutionEventTypes.COMPENSATION_STARTED, compensationStarted.type());
        assertEquals(RunStatus.COMPENSATING.name(), compensationStarted.message());
        assertEquals(List.of("node-1"), compensationStarted.metadata().get("nodesToCompensate"));
        assertEquals(1, compensationStarted.metadata().get("nodesToCompensateCount"));
        assertEquals(0.0, runFailureCounter("other"));
        assertTrue(update.await());
        assertEquals("run-compensating", update.message().getString("reason"));
    }

    @Test
    void failRun_whenAlreadyFailedWakesRunWithoutMutation() throws Exception {
        WorkflowRun run = runningRun();
        run.fail(new ErrorInfo("OLD_FAILURE", "old failure", "", Map.of()));
        runRepository.run = run;
        RunUpdateCapture update = captureRunUpdates();

        WorkflowRun returned = runManager.failRun(
                run.getId(),
                TENANT,
                new ErrorInfo("NEW_FAILURE", "new failure", "", Map.of()))
                .await().indefinitely();

        assertEquals(run, returned);
        assertEquals(RunStatus.FAILED, run.getStatus());
        assertEquals(0, runRepository.updateCount.get());
        assertTrue(historyRepository.appends.isEmpty());
        assertEquals(0.0, runFailureCounter("other"));
        assertTrue(update.await());
        assertEquals(run.getId().value(), update.message().getString("runId"));
        assertEquals(TENANT.value(), update.message().getString("tenantId"));
        assertEquals("run-fail-already-terminal", update.message().getString("reason"));
    }

    @Test
    void onNodeExecutionCompleted_validatesTokenBeforeApplyingResult() {
        WorkflowRun run = runningRun();
        runRepository.run = run;
        ExecutionToken token = runManager.createExecutionToken(run.getId(), nodeId, 1)
                .await().indefinitely();
        DefaultNodeExecutionResult result = new DefaultNodeExecutionResult(
                run.getId(),
                nodeId,
                1,
                NodeExecutionStatus.COMPLETED,
                Map.of("key", "value"),
                null,
                token);

        runManager.onNodeExecutionCompleted(result, token.value()).await().indefinitely();

        assertEquals(NodeExecutionStatus.COMPLETED, run.getNodeExecution(nodeId).getStatus());
        assertEquals(1, runRepository.updateCount.get());
        assertEquals(ExecutionEventTypes.NODE_COMPLETED, historyRepository.appends.getFirst().type());
    }

    @Test
    void onNodeExecutionCompletedWithOutcome_infersTenantFromExecutionToken() {
        WorkflowRun run = runningRun();
        runRepository.run = run;
        ExecutionToken token = runManager.createExecutionToken(run.getId(), TENANT, nodeId, 1)
                .await().indefinitely();
        DefaultNodeExecutionResult result = new DefaultNodeExecutionResult(
                run.getId(),
                nodeId,
                1,
                NodeExecutionStatus.COMPLETED,
                Map.of("key", "value"),
                null,
                token);

        NodeResultHandlingOutcome outcome = runManager.onNodeExecutionCompletedWithOutcome(result, token.value())
                .await().indefinitely();

        assertEquals(TENANT, outcome.tenantId());
        assertEquals(TENANT, runRepository.lastLockTenant);
        assertEquals(NodeExecutionResults.Acceptance.ACCEPT, outcome.acceptance());
        assertEquals(NodeExecutionStatus.COMPLETED, run.getNodeExecution(nodeId).getStatus());
    }

    @Test
    void onNodeExecutionCompleted_rejectsExplicitTenantMismatchBeforeLockingRun() {
        WorkflowRun run = runningRun();
        runRepository.run = run;
        TenantId otherTenant = TenantId.of("other-tenant");
        ExecutionToken token = runManager.tokenService.issue(run.getId(), TENANT, nodeId, 1)
                .await().indefinitely();
        DefaultNodeExecutionResult result = new DefaultNodeExecutionResult(
                run.getId(),
                nodeId,
                1,
                NodeExecutionStatus.COMPLETED,
                Map.of("key", "value"),
                null,
                token);

        assertThrows(SecurityException.class,
                () -> runManager.onNodeExecutionCompletedWithOutcome(result, otherTenant, token.value())
                        .await().indefinitely());

        assertNull(runRepository.lastLockTenant);
        assertEquals(NodeExecutionStatus.RUNNING, run.getNodeExecution(nodeId).getStatus());
        assertEquals(0, runRepository.updateCount.get());
        assertTrue(historyRepository.appends.isEmpty());
    }

    @Test
    void onNodeExecutionCompleted_rejectsUnstoredToken() {
        WorkflowRun run = runningRun();
        runRepository.run = run;
        ExecutionToken token = new ExecutionToken(
                "unknown-token",
                run.getId(),
                nodeId,
                1,
                Instant.now().plusSeconds(60));
        DefaultNodeExecutionResult result = new DefaultNodeExecutionResult(
                run.getId(),
                nodeId,
                1,
                NodeExecutionStatus.COMPLETED,
                Map.of("key", "value"),
                null,
                token);

        assertThrows(SecurityException.class,
                () -> runManager.onNodeExecutionCompleted(result, token.value()).await().indefinitely());

        assertEquals(NodeExecutionStatus.RUNNING, run.getNodeExecution(nodeId).getStatus());
        assertEquals(0, runRepository.updateCount.get());
        assertTrue(historyRepository.appends.isEmpty());
    }

    @Test
    void onExternalSignal_withTenantRejectsCallbackTenantMismatchBeforeLockingRun() {
        WorkflowRun run = suspendedRun();
        runRepository.run = run;
        CallbackRegistration registration = runManager.callbackService.register(
                run.getId(),
                TENANT,
                nodeId,
                CallbackConfig.webhook("https://example.test/callback")).await().indefinitely();
        runRepository.lastLockTenant = null;
        TenantId otherTenant = TenantId.of("other-tenant");

        assertThrows(SecurityException.class,
                () -> runManager.onExternalSignal(run.getId(), otherTenant, externalSignal(), registration.callbackToken())
                        .await().indefinitely());

        assertNull(runRepository.lastLockTenant);
        assertEquals(RunStatus.SUSPENDED, run.getStatus());
        assertEquals(0, runRepository.updateCount.get());
        assertTrue(historyRepository.appends.isEmpty());
    }

    @Test
    void onExternalSignal_withTenantProcessesCallbackOnce() {
        WorkflowRun run = suspendedRun();
        runRepository.run = run;
        CallbackRegistration registration = runManager.callbackService.register(
                run.getId(),
                TENANT,
                nodeId,
                CallbackConfig.webhook("https://example.test/callback")).await().indefinitely();
        String idempotencyKey = BearerTokenHash.sha256(registration.callbackToken());

        runManager.onExternalSignal(run.getId(), TENANT, externalSignal(), registration.callbackToken())
                .await().indefinitely();
        runManager.onExternalSignal(run.getId(), TENANT, externalSignal(), registration.callbackToken())
                .await().indefinitely();

        assertEquals(RunStatus.RUNNING, run.getStatus());
        assertEquals("yes", run.getContext().getVariable("approval.result"));
        assertEquals(1, runRepository.updateCount.get());
        assertEquals(1, historyRepository.appends.size());
        assertEquals(ExecutionEventTypes.SIGNAL_RECEIVED, historyRepository.appends.getFirst().type());
        assertEquals(idempotencyKey, historyRepository.appends.getFirst().metadata().get("idempotencyKey"));
        assertEquals("test", historyRepository.appends.getFirst().metadata().get("externalSource"));
        assertEquals("human_approval", historyRepository.appends.getFirst().metadata().get("externalSignalType"));
        assertEquals(false, historyRepository.appends.getFirst().metadata().get("externalSignaturePresent"));
        assertFalse(historyRepository.appends.getFirst().metadata().containsKey("signature"));
        assertTrue(historyRepository.isExternalSignalProcessed(run.getId(), TENANT, idempotencyKey)
                .await().indefinitely());
    }

    @Test
    void onExternalSignal_duringCompensationRecordsIgnoredHistoryWithoutMutatingRun() {
        WorkflowRun run = WorkflowRun.create(TENANT, workflowWithCompensation(), Map.of());
        run.start();
        run.startNode(nodeId, 1);
        runRepository.run = run;
        CallbackRegistration registration = runManager.callbackService.register(
                run.getId(),
                TENANT,
                nodeId,
                CallbackConfig.webhook("https://example.test/callback")).await().indefinitely();
        run.cancel("operator stop");
        String idempotencyKey = BearerTokenHash.sha256(registration.callbackToken());

        runManager.onExternalSignal(run.getId(), TENANT, externalSignal(), registration.callbackToken())
                .await().indefinitely();
        runManager.onExternalSignal(run.getId(), TENANT, externalSignal(), registration.callbackToken())
                .await().indefinitely();

        assertEquals(RunStatus.COMPENSATING, run.getStatus());
        assertNull(run.getContext().getVariable("approval.result"));
        assertEquals(0, runRepository.updateCount.get());
        assertEquals(1, historyRepository.appends.size());
        HistoryAppend append = historyRepository.appends.getFirst();
        assertEquals(ExecutionEventTypes.SIGNAL_IGNORED, append.type());
        assertEquals("Run is not accepting signals: COMPENSATING", append.message());
        assertEquals("COMPENSATING", append.metadata().get("runStatus"));
        assertEquals(idempotencyKey, append.metadata().get("idempotencyKey"));
        assertEquals("human_approval", append.metadata().get("externalSignalType"));
        assertTrue(historyRepository.isExternalSignalProcessed(run.getId(), TENANT, idempotencyKey)
                .await().indefinitely());
    }

    @Test
    void onExternalSignal_preservesProducerTimestampInHistory() {
        WorkflowRun run = suspendedRun();
        runRepository.run = run;
        Instant producedAt = Instant.parse("2026-05-26T01:02:03Z");
        CallbackRegistration registration = runManager.callbackService.register(
                run.getId(),
                TENANT,
                nodeId,
                CallbackConfig.webhook("https://example.test/callback")).await().indefinitely();

        runManager.onExternalSignal(run.getId(), TENANT, externalSignal(producedAt), registration.callbackToken())
                .await().indefinitely();

        assertEquals(producedAt.toString(), historyRepository.appends.getFirst().metadata().get("timestamp"));
    }

    @Test
    void onExternalSignal_defaultsBlankSignalTypeInHistory() {
        WorkflowRun run = suspendedRun();
        runRepository.run = run;
        CallbackRegistration registration = runManager.callbackService.register(
                run.getId(),
                TENANT,
                nodeId,
                CallbackConfig.webhook("https://example.test/callback")).await().indefinitely();

        runManager.onExternalSignal(
                run.getId(),
                TENANT,
                externalSignal(Instant.parse("2026-05-26T01:02:03Z"), " ", "test", ""),
                registration.callbackToken()).await().indefinitely();

        HistoryAppend append = historyRepository.appends.getFirst();
        assertEquals("external_signal", append.message());
        assertEquals("external_signal", append.metadata().get("externalSignalType"));
    }

    @Test
    void onExternalSignal_trimsSignalTypeInHistory() {
        WorkflowRun run = suspendedRun();
        runRepository.run = run;
        CallbackRegistration registration = runManager.callbackService.register(
                run.getId(),
                TENANT,
                nodeId,
                CallbackConfig.webhook("https://example.test/callback")).await().indefinitely();

        runManager.onExternalSignal(
                run.getId(),
                TENANT,
                externalSignal(Instant.parse("2026-05-26T01:02:03Z"), " human_approval ", "test", ""),
                registration.callbackToken()).await().indefinitely();

        HistoryAppend append = historyRepository.appends.getFirst();
        assertEquals("human_approval", append.message());
        assertEquals("human_approval", append.metadata().get("externalSignalType"));
    }

    @Test
    void signal_persistsMutationAndAppendsPayload() {
        WorkflowRun run = suspendedRun();
        runRepository.run = run;
        Signal signal = new Signal("approved", nodeId, Map.of("approval.result", "yes"), Instant.now());

        runManager.signal(run.getId(), signal).await().indefinitely();
        runManager.signal(run.getId(), signal).await().indefinitely();

        assertEquals(RunStatus.RUNNING, run.getStatus());
        assertEquals("yes", run.getContext().getVariable("approval.result"));
        assertEquals(1, runRepository.updateCount.get());
        assertEquals(1, historyRepository.appends.size());
        HistoryAppend append = historyRepository.appends.getFirst();
        assertEquals(ExecutionEventTypes.SIGNAL_RECEIVED, append.type());
        assertEquals("node-1", append.metadata().get("targetNodeId"));
        assertTrue(((String) append.metadata().get("idempotencyKey")).startsWith("raw:"));
    }

    @Test
    void signal_doesNotPersistRunOrMarkProcessedWhenAuditAppendFails() {
        RuntimeException failure = new RuntimeException("audit unavailable");
        WorkflowRun run = suspendedRun();
        runRepository.run = run;
        historyRepository.appendFailure = failure;
        Signal signal = new Signal("approved", nodeId, Map.of("approval.result", "yes"), Instant.now());

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> runManager.signal(run.getId(), TENANT, signal).await().indefinitely());

        assertSame(failure, thrown);
        assertEquals(RunStatus.SUSPENDED, run.getStatus());
        assertNull(run.getContext().getVariable("approval.result"));
        assertEquals(0, runRepository.updateCount.get());
        assertTrue(historyRepository.appends.isEmpty());
        assertTrue(historyRepository.processedExternalSignals.isEmpty());
    }

    @Test
    void signalMetadata_namespacesPayloadAndPreservesReservedKeys() {
        WorkflowRun run = suspendedRun();
        runRepository.run = run;
        Instant timestamp = Instant.parse("2026-05-26T01:02:03Z");
        Map<String, Object> payload = Map.of(
                "approval.result", "yes",
                "externalSource", "payload-source",
                "idempotencyKey", "payload-id",
                "targetNodeId", "payload-node",
                "timestamp", "payload-time");

        runManager.signal(run.getId(), new Signal("approved", nodeId, payload, timestamp))
                .await().indefinitely();

        HistoryAppend append = historyRepository.appends.getFirst();
        assertEquals("yes", append.metadata().get("approval.result"));
        assertEquals(payload, append.metadata().get("payload"));
        assertEquals("node-1", append.metadata().get("targetNodeId"));
        assertEquals(timestamp.toString(), append.metadata().get("timestamp"));
        assertTrue(((String) append.metadata().get("idempotencyKey")).startsWith("raw:"));
        assertFalse(append.metadata().containsKey("externalSource"));
    }

    @Test
    void signal_processesSameLogicalSignalWithDifferentTimestampsOnce() {
        WorkflowRun run = suspendedRun();
        runRepository.run = run;
        Map<String, Object> payload = Map.of("approval.result", "yes");

        runManager.signal(
                run.getId(),
                new Signal("approved", nodeId, payload, Instant.parse("2026-05-26T01:02:03Z")))
                .await().indefinitely();
        runManager.signal(
                run.getId(),
                new Signal("approved", nodeId, payload, Instant.parse("2026-05-26T01:02:04Z")))
                .await().indefinitely();

        assertEquals(RunStatus.RUNNING, run.getStatus());
        assertEquals("yes", run.getContext().getVariable("approval.result"));
        assertEquals(1, runRepository.updateCount.get());
        assertEquals(1, historyRepository.appends.size());
        assertEquals("2026-05-26T01:02:03Z", historyRepository.appends.getFirst().metadata().get("timestamp"));
    }

    @Test
    void signal_withExplicitIdempotencyKeyIgnoresChangedReplayPayload() {
        WorkflowRun run = suspendedRun();
        runRepository.run = run;

        runManager.signal(
                run.getId(),
                new Signal(
                        "approved",
                        nodeId,
                        Map.of("approval.result", "yes"),
                        Instant.parse("2026-05-26T01:02:03Z"),
                        "client-signal-1"))
                .await().indefinitely();
        runManager.signal(
                run.getId(),
                new Signal(
                        "approved",
                        nodeId,
                        Map.of("approval.result", "no"),
                        Instant.parse("2026-05-26T01:02:04Z"),
                        " client-signal-1 "))
                .await().indefinitely();

        assertEquals(RunStatus.RUNNING, run.getStatus());
        assertEquals("yes", run.getContext().getVariable("approval.result"));
        assertEquals(1, runRepository.updateCount.get());
        assertEquals(1, historyRepository.appends.size());
        HistoryAppend append = historyRepository.appends.getFirst();
        assertEquals("raw-client:" + BearerTokenHash.sha256("client-signal-1"),
                append.metadata().get("idempotencyKey"));
        assertEquals(BearerTokenHash.sha256("client-signal-1"),
                append.metadata().get("clientIdempotencyKeyHash"));
    }

    @Test
    void signal_withTenantProcessesReplayOnce() {
        WorkflowRun run = suspendedRun();
        runRepository.run = run;
        Signal signal = new Signal("approved", nodeId, Map.of("approval.result", "yes"), Instant.now());

        runManager.signal(run.getId(), TENANT, signal).await().indefinitely();
        runManager.signal(run.getId(), TENANT, signal).await().indefinitely();

        assertEquals(TENANT, runRepository.lastLockTenant);
        assertEquals(RunStatus.RUNNING, run.getStatus());
        assertEquals("yes", run.getContext().getVariable("approval.result"));
        assertEquals(1, runRepository.updateCount.get());
        assertEquals(1, historyRepository.appends.size());
        HistoryAppend append = historyRepository.appends.getFirst();
        assertEquals(ExecutionEventTypes.SIGNAL_RECEIVED, append.type());
        assertEquals("node-1", append.metadata().get("targetNodeId"));
        assertTrue(((String) append.metadata().get("idempotencyKey")).startsWith("raw:"));
    }

    @Test
    void signal_afterTerminalCancellationRecordsIgnoredHistoryWithoutMutatingRun() {
        WorkflowRun run = suspendedRun();
        run.cancel("operator stop");
        runRepository.run = run;
        Signal signal = new Signal(
                "approved",
                nodeId,
                Map.of("approval.result", "yes"),
                Instant.parse("2026-05-26T01:02:03Z"),
                "client-signal-1");

        runManager.signal(run.getId(), TENANT, signal).await().indefinitely();
        runManager.signal(run.getId(), TENANT, signal).await().indefinitely();

        assertEquals(RunStatus.CANCELLED, run.getStatus());
        assertNull(run.getContext().getVariable("approval.result"));
        assertEquals(0, runRepository.updateCount.get());
        assertEquals(1, historyRepository.appends.size());
        HistoryAppend append = historyRepository.appends.getFirst();
        assertEquals(ExecutionEventTypes.SIGNAL_IGNORED, append.type());
        assertEquals("Run is not accepting signals: CANCELLED", append.message());
        assertEquals("CANCELLED", append.metadata().get("runStatus"));
        assertEquals("node-1", append.metadata().get("targetNodeId"));
        assertEquals("raw-client:" + BearerTokenHash.sha256("client-signal-1"),
                append.metadata().get("idempotencyKey"));
        assertTrue(historyRepository.isExternalSignalProcessed(
                run.getId(),
                TENANT,
                "raw-client:" + BearerTokenHash.sha256("client-signal-1")).await().indefinitely());
    }

    @Test
    void resumeRun_acceptsNullResumeData() {
        WorkflowRun run = suspendedRun();
        runRepository.run = run;

        runManager.resumeRun(run.getId(), TENANT, null, null).await().indefinitely();

        assertEquals(RunStatus.RUNNING, run.getStatus());
        assertEquals(1, runRepository.updateCount.get());
        assertEventBatch(WorkflowResumedEvent.class);
        assertTrue(run.getUncommittedEvents().isEmpty());
        HistoryAppend append = historyRepository.appends.getFirst();
        assertEquals(ExecutionEventTypes.STATUS_CHANGED, append.type());
        assertEquals(RunStatus.RUNNING.name(), append.message());
        assertEquals("", append.metadata().get("humanTaskId"));
    }

    @Test
    void resumeRun_publishesTenantAwareRunUpdate() throws Exception {
        WorkflowRun run = suspendedRun();
        runRepository.run = run;
        CountDownLatch updatePublished = new CountDownLatch(1);
        final JsonObject[] payload = new JsonObject[1];
        vertx.eventBus().<Object>consumer(WorkflowRunUpdateEvent.ADDRESS)
                .handler(message -> {
                    if (message.body() instanceof JsonObject json) {
                        payload[0] = json;
                        updatePublished.countDown();
                    }
                });

        runManager.resumeRun(run.getId(), TENANT, Map.of("approved", true), "human-task-1")
                .await().indefinitely();

        assertTrue(updatePublished.await(2, TimeUnit.SECONDS));
        assertEquals(run.getId().value(), payload[0].getString("runId"));
        assertEquals(TENANT.value(), payload[0].getString("tenantId"));
        assertEquals("run-resumed", payload[0].getString("reason"));
    }

    @Test
    void resumeRun_whenAlreadyRunningWakesRunWithoutMutation() throws Exception {
        WorkflowRun run = runningRun();
        runRepository.run = run;
        CountDownLatch updatePublished = new CountDownLatch(1);
        final JsonObject[] payload = new JsonObject[1];
        vertx.eventBus().<Object>consumer(WorkflowRunUpdateEvent.ADDRESS)
                .handler(message -> {
                    if (message.body() instanceof JsonObject json) {
                        payload[0] = json;
                        updatePublished.countDown();
                    }
                });

        WorkflowRun returned = runManager.resumeRun(run.getId(), TENANT, Map.of("approved", true), "human-task-1")
                .await().indefinitely();

        assertEquals(run, returned);
        assertEquals(RunStatus.RUNNING, run.getStatus());
        assertEquals(0, runRepository.updateCount.get());
        assertTrue(historyRepository.appends.isEmpty());
        assertTrue(updatePublished.await(2, TimeUnit.SECONDS));
        assertEquals(run.getId().value(), payload[0].getString("runId"));
        assertEquals(TENANT.value(), payload[0].getString("tenantId"));
        assertEquals("run-resume-already-active", payload[0].getString("reason"));
    }

    private RunUpdateCapture captureRunUpdates() {
        CountDownLatch updatePublished = new CountDownLatch(1);
        final JsonObject[] payload = new JsonObject[1];
        vertx.eventBus().<Object>consumer(WorkflowRunUpdateEvent.ADDRESS)
                .handler(message -> {
                    if (message.body() instanceof JsonObject json) {
                        payload[0] = json;
                        updatePublished.countDown();
                    }
                });
        return new RunUpdateCapture(updatePublished, payload);
    }

    private record RunUpdateCapture(CountDownLatch latch, JsonObject[] payload) {
        boolean await() throws InterruptedException {
            return latch.await(2, TimeUnit.SECONDS);
        }

        JsonObject message() {
            return payload[0];
        }
    }

    private ExternalSignal externalSignal() {
        return externalSignal(Instant.now());
    }

    private ExternalSignal externalSignal(Instant timestamp) {
        return externalSignal(timestamp, "human_approval", "test", "");
    }

    private ExternalSignal externalSignal(Instant timestamp, String signalType, String source, String signature) {
        return new ExternalSignal() {
            @Override
            public String getSignalType() {
                return signalType;
            }

            @Override
            public NodeId getTargetNodeId() {
                return nodeId;
            }

            @Override
            public String getSource() {
                return source;
            }

            @Override
            public Map<String, Object> getPayload() {
                return Map.of("approval.result", "yes");
            }

            @Override
            public Instant getTimestamp() {
                return timestamp;
            }

            @Override
            public String getSignature() {
                return signature;
            }
        };
    }

    private WorkflowRun runningRun() {
        WorkflowRun run = WorkflowRun.create(TENANT, workflow(), Map.of());
        run.start();
        run.startNode(nodeId, 1);
        run.markEventsAsCommitted();
        return run;
    }

    private WorkflowRun suspendedRun() {
        WorkflowRun run = runningRun();
        run.suspend("waiting", nodeId);
        run.markEventsAsCommitted();
        return run;
    }

    private WorkflowDefinition workflow() {
        return workflow(node());
    }

    private WorkflowDefinition workflow(NodeDefinition node) {
        return workflow(node, CompensationPolicy.disabled());
    }

    private WorkflowDefinition workflowWithCompensation() {
        return workflow(node(), CompensationPolicy.enabledDefault());
    }

    private WorkflowDefinition workflowWithCompensationChain() {
        NodeDefinition first = node();
        NodeDefinition second = new NodeDefinition(
                NodeId.of("node-2"),
                "node-2",
                NodeType.TASK,
                "local",
                Map.of(),
                List.of(first.id()),
                List.of(),
                RetryPolicy.none(),
                Duration.ZERO,
                true);
        return workflow(List.of(first, second), CompensationPolicy.enabledDefault());
    }

    private WorkflowDefinition workflow(NodeDefinition node, CompensationPolicy compensationPolicy) {
        return workflow(List.of(node), compensationPolicy);
    }

    private WorkflowDefinition workflow(List<NodeDefinition> nodes, CompensationPolicy compensationPolicy) {
        return new WorkflowDefinition(
                WorkflowDefinitionId.of("wf-test"),
                TENANT,
                "test",
                "1.0.0",
                null,
                WorkflowMode.FLOW,
                nodes,
                Map.of("topic", new InputDefinition("topic", "string", false, "orders", null)),
                Map.of(),
                null,
                RetryPolicy.none(),
                compensationPolicy);
    }

    private WorkflowDefinition legacyWorkflowWithoutNodes() {
        return workflow(List.of(), CompensationPolicy.disabled());
    }

    private void assertEventBatch(Class<?>... eventTypes) {
        assertEquals(1, historyRepository.eventBatches.size());
        EventBatch batch = historyRepository.eventBatches.getFirst();
        assertEquals(TENANT, batch.tenantId());
        assertEquals(eventTypes.length, batch.events().size());
        for (int index = 0; index < eventTypes.length; index++) {
            assertTrue(eventTypes[index].isInstance(batch.events().get(index)));
        }
    }

    private double runFailureCounter(String reason) {
        var counter = meterRegistry.find("gamelan.workflow.run.failures")
                .tag("reason", reason)
                .counter();
        return counter != null ? counter.count() : 0.0;
    }

    private NodeDefinition node() {
        return new NodeDefinition(
                nodeId,
                "node-1",
                NodeType.TASK,
                "local",
                Map.of(),
                List.of(),
                List.of(),
                RetryPolicy.none(),
                Duration.ZERO,
                false);
    }

    private NodeDefinition criticalNode() {
        return new NodeDefinition(
                nodeId,
                "node-1",
                NodeType.TASK,
                "local",
                Map.of(),
                List.of(),
                List.of(),
                RetryPolicy.none(),
                Duration.ZERO,
                true);
    }

    private NodeDefinition retryingNode() {
        return new NodeDefinition(
                nodeId,
                "node-1",
                NodeType.TASK,
                "local",
                Map.of(),
                List.of(),
                List.of(),
                new RetryPolicy(2, Duration.ZERO, Duration.ZERO, 1.0, List.of()),
                Duration.ZERO,
                false);
    }

    private NodeDefinition delayedRetryingNode() {
        return new NodeDefinition(
                nodeId,
                "node-1",
                NodeType.TASK,
                "local",
                Map.of(),
                List.of(),
                List.of(),
                new RetryPolicy(2, Duration.ofSeconds(30), Duration.ofSeconds(30), 1.0, List.of()),
                Duration.ZERO,
                false);
    }

    private static final class FailingDefinitionRegistry extends WorkflowDefinitionRegistry {
        @Override
        public Uni<WorkflowDefinition> getDefinition(WorkflowDefinitionId id, TenantId tenantId) {
            return Uni.createFrom().failure(new UnsupportedOperationException("not supported"));
        }
    }

    private static final class FixedDefinitionRegistry extends WorkflowDefinitionRegistry {
        private final WorkflowDefinition definition;

        private FixedDefinitionRegistry(WorkflowDefinition definition) {
            this.definition = definition;
        }

        @Override
        public Uni<WorkflowDefinition> getDefinition(WorkflowDefinitionId id, TenantId tenantId) {
            return Uni.createFrom().item(definition);
        }
    }

    private record HistoryAppend(
            WorkflowRunId runId,
            String type,
            String message,
            Map<String, Object> metadata) {
    }

    private record EventBatch(
            WorkflowRunId runId,
            TenantId tenantId,
            List<ExecutionEvent> events) {
    }

    private static final class RecordingExecutionHistoryRepository implements ExecutionHistoryRepository {
        final List<HistoryAppend> appends = new ArrayList<>();
        final List<EventBatch> eventBatches = new ArrayList<>();
        final java.util.Set<String> processedNodeResults = new java.util.HashSet<>();
        final java.util.Set<String> processedExternalSignals = new java.util.HashSet<>();
        boolean nodeResultProcessed;
        RuntimeException appendFailure;
        RuntimeException eventAppendFailure;

        @Override
        public Uni<Void> append(WorkflowRunId runId, String type, String message, Map<String, Object> metadata) {
            if (appendFailure != null) {
                return Uni.createFrom().failure(appendFailure);
            }
            appends.add(new HistoryAppend(runId, type, message, metadata));
            return Uni.createFrom().voidItem();
        }

        @Override
        public Uni<Void> appendEvents(WorkflowRunId runId, List<ExecutionEvent> events) {
            if (eventAppendFailure != null) {
                return Uni.createFrom().failure(eventAppendFailure);
            }
            eventBatches.add(new EventBatch(runId, null, List.copyOf(events)));
            return Uni.createFrom().voidItem();
        }

        @Override
        public Uni<Void> appendEvents(WorkflowRunId runId, TenantId tenantId, List<ExecutionEvent> events) {
            if (eventAppendFailure != null) {
                return Uni.createFrom().failure(eventAppendFailure);
            }
            eventBatches.add(new EventBatch(runId, tenantId, List.copyOf(events)));
            return Uni.createFrom().voidItem();
        }

        @Override
        public Uni<ExecutionHistory> load(WorkflowRunId runId) {
            return Uni.createFrom().nullItem();
        }

        @Override
        public Uni<Boolean> isNodeResultProcessed(WorkflowRunId runId, NodeId nodeId, int attempt) {
            return Uni.createFrom().item(nodeResultProcessed || processedNodeResults.contains(processedKey(
                    runId,
                    nodeId,
                    attempt)));
        }

        @Override
        public Uni<Boolean> markNodeResultProcessed(WorkflowRunId runId, NodeId nodeId, int attempt) {
            return Uni.createFrom().item(processedNodeResults.add(processedKey(runId, nodeId, attempt)));
        }

        @Override
        public Uni<Boolean> isExternalSignalProcessed(WorkflowRunId runId, String idempotencyKey) {
            return Uni.createFrom().item(processedExternalSignals.contains(signalKey(runId, null, idempotencyKey)));
        }

        @Override
        public Uni<Boolean> isExternalSignalProcessed(
                WorkflowRunId runId,
                TenantId tenantId,
                String idempotencyKey) {
            return Uni.createFrom().item(processedExternalSignals.contains(signalKey(runId, tenantId, idempotencyKey)));
        }

        @Override
        public Uni<Boolean> markExternalSignalProcessed(WorkflowRunId runId, String idempotencyKey) {
            return Uni.createFrom().item(processedExternalSignals.add(signalKey(runId, null, idempotencyKey)));
        }

        @Override
        public Uni<Boolean> markExternalSignalProcessed(
                WorkflowRunId runId,
                TenantId tenantId,
                String idempotencyKey) {
            return Uni.createFrom().item(processedExternalSignals.add(signalKey(runId, tenantId, idempotencyKey)));
        }

        private String processedKey(WorkflowRunId runId, NodeId nodeId, int attempt) {
            return runId.value() + ":" + nodeId.value() + ":" + attempt;
        }

        private String signalKey(WorkflowRunId runId, TenantId tenantId, String idempotencyKey) {
            return (tenantId != null ? tenantId.value() : "") + ":" + runId.value() + ":" + idempotencyKey;
        }
    }

    private record RetrySchedule(WorkflowRunId runId, TenantId tenantId, NodeId nodeId, int attempt, Duration delay) {
    }

    private static final class RecordingWakeupPublisher implements tech.kayys.gamelan.engine.event.WorkflowRunWakeupPublisher {
        final List<WorkflowRunUpdateEvent> events = new ArrayList<>();

        @Override
        public Uni<Void> publish(WorkflowRunUpdateEvent event) {
            events.add(event);
            return Uni.createFrom().voidItem();
        }
    }

    private static final class RecordingRetryManager implements RetryManager {
        final List<RetrySchedule> schedules = new ArrayList<>();

        @Override
        public Uni<Void> scheduleRetry(WorkflowRunId runId, NodeId nodeId, Duration delay) {
            return scheduleRetry(runId, null, nodeId, delay);
        }

        @Override
        public Uni<Void> scheduleRetry(WorkflowRunId runId, TenantId tenantId, NodeId nodeId, Duration delay) {
            schedules.add(new RetrySchedule(runId, tenantId, nodeId, 0, delay));
            return Uni.createFrom().voidItem();
        }

        @Override
        public Uni<Void> scheduleRetry(WorkflowRunId runId, TenantId tenantId, NodeId nodeId, int attempt,
                Duration delay) {
            schedules.add(new RetrySchedule(runId, tenantId, nodeId, attempt, delay));
            return Uni.createFrom().voidItem();
        }
    }

    private static final class RecordingWorkflowRunRepository implements WorkflowRunRepository {
        WorkflowRun run;
        final AtomicInteger updateCount = new AtomicInteger();
        final Map<String, StoredExecutionToken> tokens = new HashMap<>();
        final List<CallbackRegistration> callbacks = new ArrayList<>();
        TenantId lastLockTenant;
        RuntimeException updateFailure;

        @Override
        public Uni<WorkflowRun> persist(WorkflowRun run) {
            this.run = run;
            return Uni.createFrom().item(run);
        }

        @Override
        public Uni<WorkflowRun> update(WorkflowRun run) {
            updateCount.incrementAndGet();
            if (updateFailure != null) {
                return Uni.createFrom().failure(updateFailure);
            }
            this.run = run;
            return Uni.createFrom().item(run);
        }

        @Override
        public Uni<WorkflowRun> findById(WorkflowRunId id) {
            return Uni.createFrom().item(run);
        }

        @Override
        public Uni<WorkflowRun> findById(WorkflowRunId id, TenantId tenantId) {
            return Uni.createFrom().item(run);
        }

        @Override
        public <T> Uni<T> withLock(WorkflowRunId runId, Function<WorkflowRun, Uni<T>> action) {
            lastLockTenant = null;
            return action.apply(run);
        }

        @Override
        public <T> Uni<T> withLock(WorkflowRunId runId, TenantId tenantId, Function<WorkflowRun, Uni<T>> action) {
            lastLockTenant = tenantId;
            return action.apply(run);
        }

        @Override
        public Uni<WorkflowRunSnapshot> snapshot(WorkflowRunId runId, TenantId tenantId) {
            return Uni.createFrom().item(run.createSnapshot());
        }

        @Override
        public Uni<List<WorkflowRun>> query(
                TenantId tenantId,
                WorkflowDefinitionId definitionId,
                RunStatus status,
                int page,
                int size) {
            return Uni.createFrom().item(List.of(run));
        }

        @Override
        public Uni<Long> countActiveRuns(TenantId tenantId) {
            return Uni.createFrom().item(run.getStatus().isActive() ? 1L : 0L);
        }

        @Override
        public Uni<Void> storeToken(ExecutionToken token) {
            tokens.put(ExecutionTokenHash.sha256(token.value()), StoredExecutionToken.from(token));
            return Uni.createFrom().voidItem();
        }

        @Override
        public Uni<Boolean> validateToken(ExecutionToken token) {
            StoredExecutionToken stored = tokens.get(ExecutionTokenHash.sha256(token.value()));
            return Uni.createFrom().item(stored != null
                    && !stored.isExpired()
                    && stored.matches(token));
        }

        @Override
        public Uni<Void> storeCallback(CallbackRegistration callback) {
            callbacks.add(callback);
            return Uni.createFrom().voidItem();
        }

        @Override
        public Uni<Boolean> validateCallback(WorkflowRunId runId, String token) {
            return Uni.createFrom().item(callbacks.stream()
                    .anyMatch(callback -> callback.runId().equals(runId)
                            && callback.callbackToken().equals(token)
                            && Instant.now().isBefore(callback.expiresAt())));
        }

        @Override
        public Uni<Boolean> validateCallback(WorkflowRunId runId, TenantId tenantId, String token) {
            return Uni.createFrom().item(callbacks.stream()
                    .anyMatch(callback -> callback.runId().equals(runId)
                            && callback.callbackToken().equals(token)
                            && (callback.tenantId() == null || callback.tenantId().equals(tenantId))
                            && Instant.now().isBefore(callback.expiresAt())));
        }

        @Override
        public Uni<Void> updateContextVariable(WorkflowRunId runId, String key, Object value) {
            return Uni.createFrom().voidItem();
        }

        @Override
        public Uni<Void> updateNodeExecution(WorkflowRunId runId, NodeId nodeId, NodeExecutionSnapshot snapshot) {
            return Uni.createFrom().voidItem();
        }

        private record StoredExecutionToken(
                WorkflowRunId runId,
                TenantId tenantId,
                NodeId nodeId,
                int attempt,
                Instant expiresAt) {

                static StoredExecutionToken from(ExecutionToken token) {
                        return new StoredExecutionToken(
                                        token.runId(),
                                        token.tenantId(),
                                        token.nodeId(),
                                        token.attempt(),
                                        token.expiresAt());
                }

            boolean isExpired() {
                return Instant.now().isAfter(expiresAt);
            }

                boolean matches(ExecutionToken token) {
                        return runId.equals(token.runId())
                                        && tenantMatches(token)
                                        && nodeId.equals(token.nodeId())
                                        && attempt == token.attempt();
                }

                private boolean tenantMatches(ExecutionToken token) {
                        return tenantId == null || tenantId.equals(token.tenantId());
                }
        }
    }
}
