package tech.kayys.gamelan.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
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
import tech.kayys.gamelan.engine.SystemClock;
import tech.kayys.gamelan.engine.callback.CallbackRegistration;
import tech.kayys.gamelan.engine.event.EventStore;
import tech.kayys.gamelan.engine.event.ExecutionEvent;
import tech.kayys.gamelan.engine.event.WorkflowRunUpdateEvent;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupPublisher;
import tech.kayys.gamelan.engine.execution.ExecutionToken;
import tech.kayys.gamelan.engine.node.NodeDefinition;
import tech.kayys.gamelan.engine.node.NodeExecutionSnapshot;
import tech.kayys.gamelan.engine.node.NodeExecutionStatus;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.node.NodeType;
import tech.kayys.gamelan.engine.repository.WorkflowDefinitionRepository;
import tech.kayys.gamelan.engine.repository.WorkflowRecoveryLease;
import tech.kayys.gamelan.engine.repository.WorkflowRecoveryLeaseRepository;
import tech.kayys.gamelan.engine.repository.WorkflowRunRepository;
import tech.kayys.gamelan.engine.repository.WorkflowRunRecoveryCursor;
import tech.kayys.gamelan.engine.repository.WorkflowRunRecoveryPage;
import tech.kayys.gamelan.engine.run.RetryPolicy;
import tech.kayys.gamelan.engine.run.RunStatus;
import tech.kayys.gamelan.engine.saga.CompensationPolicy;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinition;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinitionId;
import tech.kayys.gamelan.engine.workflow.WorkflowMode;
import tech.kayys.gamelan.engine.workflow.WorkflowReplayConsistencyChecker;
import tech.kayys.gamelan.engine.workflow.WorkflowRun;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunSnapshot;
import tech.kayys.gamelan.scheduler.RetryManager;

class WorkflowRecoveryServiceTest {

    private static final TenantId TENANT = TenantId.of("tenant-1");
    private static final NodeId NODE_ID = NodeId.of("node-1");
    private static final Instant NOW = Instant.parse("2026-05-22T00:00:00Z");

    private Vertx vertx;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
    }

    @AfterEach
    void tearDown() {
        vertx.close().await().indefinitely();
    }

    @Test
    void recoverActiveRuns_publishesWakeupForDueRetriesMissedByRetryManager() throws Exception {
        WorkflowRun run = runningRun(node(RetryPolicy.DEFAULT, Duration.ZERO));
        run.reserveNodeForDispatch(NODE_ID);
        run.failNode(NODE_ID, 1, error());
        run.getNodeExecution(NODE_ID).setRetryAt(NOW.minusSeconds(1));

        RecordingWorkflowRunRepository repository = new RecordingWorkflowRunRepository(run);
        WorkflowRecoveryService service = service(repository, new RecordingRetryManager());
        CountDownLatch wakeup = wakeupLatch(run.getId());

        WorkflowRecoveryService.RecoverySweepResult result = service.recoverActiveRuns().await().indefinitely();

        assertEquals(1, result.scannedRuns());
        assertEquals(1, result.dueRetries());
        assertEquals(0, result.staleExecutions());
        assertEquals(0, repository.updateCount.get());
        assertTrue(wakeup.await(2, TimeUnit.SECONDS));
    }

    @Test
    void recoverActiveRuns_usesInjectedWakeupPublisherWithoutEventBusCoupling() {
        WorkflowRun run = runningRun(node(RetryPolicy.DEFAULT, Duration.ZERO));
        run.reserveNodeForDispatch(NODE_ID);
        run.failNode(NODE_ID, 1, error());
        run.getNodeExecution(NODE_ID).setRetryAt(NOW.minusSeconds(1));
        RecordingWakeupPublisher wakeupPublisher = new RecordingWakeupPublisher();

        WorkflowRecoveryService service = service(new RecordingWorkflowRunRepository(run), new RecordingRetryManager());
        service.eventBus = null;
        service.wakeupPublisher = wakeupPublisher;

        WorkflowRecoveryService.RecoverySweepResult result = service.recoverActiveRuns().await().indefinitely();

        assertEquals(1, result.dueRetries());
        assertEquals(1, wakeupPublisher.events.size());
        WorkflowRunUpdateEvent event = wakeupPublisher.events.getFirst();
        assertEquals(run.getId().value(), event.runId());
        assertEquals(TENANT.value(), event.tenantId());
        assertEquals("recovery-wakeup", event.reason());
    }

    @Test
    void recoverActiveRuns_doesNotFailRunWhenWakeupPublisherFails() {
        WorkflowRun run = dueRetryRun();
        RecordingWakeupPublisher wakeupPublisher = new RecordingWakeupPublisher();
        wakeupPublisher.failWith(new IllegalStateException("publisher unavailable"));
        WorkflowRecoveryService service = service(new RecordingWorkflowRunRepository(run), new RecordingRetryManager());
        service.wakeupPublisher = wakeupPublisher;

        WorkflowRecoveryService.RecoverySweepResult result = service.recoverActiveRuns().await().indefinitely();

        assertEquals(1, result.scannedRuns());
        assertEquals(1, result.dueRetries());
        assertEquals(1, result.wakeups());
        assertEquals(0, result.failedRuns());
        assertEquals(1, wakeupPublisher.events.size());
    }

    @Test
    void recoverActiveRuns_backfillsFutureRetryWakeupWithoutMutatingOrWakingRun() {
        WorkflowRun run = runningRun(node(RetryPolicy.DEFAULT, Duration.ZERO));
        run.reserveNodeForDispatch(NODE_ID);
        run.failNode(NODE_ID, 1, error());
        run.getNodeExecution(NODE_ID).setRetryAt(NOW.plusSeconds(30));
        RecordingRetryManager retryManager = new RecordingRetryManager();
        RecordingWakeupPublisher wakeupPublisher = new RecordingWakeupPublisher();
        RecordingWorkflowRunRepository repository = new RecordingWorkflowRunRepository(run);
        WorkflowRecoveryService service = service(repository, retryManager);
        service.wakeupPublisher = wakeupPublisher;

        WorkflowRecoveryService.RecoverySweepResult result = service.recoverActiveRuns().await().indefinitely();

        assertEquals(1, result.scannedRuns());
        assertEquals(0, result.dueRetries());
        assertEquals(1, result.retryWakeups());
        assertEquals(0, repository.updateCount.get());
        assertTrue(wakeupPublisher.events.isEmpty());
        assertEquals(TENANT, retryManager.schedules.getFirst().tenantId());
        assertEquals(NODE_ID, retryManager.schedules.getFirst().nodeId());
        assertEquals(2, retryManager.schedules.getFirst().attempt());
        assertEquals(Duration.ofSeconds(30), retryManager.schedules.getFirst().delay());
    }

    @Test
    void recoverActiveRuns_reapsStaleTimedOutExecutionsAndWakesRun() throws Exception {
        WorkflowRun run = runningRun(node(RetryPolicy.none(), Duration.ofSeconds(10)));
        run.reserveNodeForDispatch(NODE_ID);
        run.getNodeExecution(NODE_ID).setStartedAt(NOW.minusSeconds(20));

        RecordingWorkflowRunRepository repository = new RecordingWorkflowRunRepository(run);
        WorkflowRecoveryService service = service(repository, new RecordingRetryManager());
        CountDownLatch wakeup = wakeupLatch(run.getId());

        WorkflowRecoveryService.RecoverySweepResult result = service.recoverActiveRuns().await().indefinitely();

        assertEquals(1, result.scannedRuns());
        assertEquals(0, result.dueRetries());
        assertEquals(1, result.staleExecutions());
        assertEquals(1, repository.updateCount.get());
        assertEquals(NodeExecutionStatus.FAILED, run.getNodeExecution(NODE_ID).getStatus());
        assertEquals("NODE_EXECUTION_TIMEOUT", run.getNodeExecution(NODE_ID).getLastError().code());
        assertTrue(wakeup.await(2, TimeUnit.SECONDS));
    }

    @Test
    void recoverActiveRuns_skipsMutationWhenReplayDriftIsDetected() {
        NodeDefinition node = node(RetryPolicy.none(), Duration.ofSeconds(10));
        WorkflowDefinition definition = workflow(node);
        WorkflowRun run = WorkflowRun.create(TENANT, definition, Map.of());
        run.start();
        run.reserveNodeForDispatch(NODE_ID);
        run.getNodeExecution(NODE_ID).setStartedAt(NOW.minusSeconds(20));

        RecordingWorkflowRunRepository repository = new RecordingWorkflowRunRepository(run);
        WorkflowRecoveryService service = service(repository, new RecordingRetryManager());
        service.definitionRepository = new RecordingWorkflowDefinitionRepository(definition);
        service.eventStore = new RecordingEventStore(List.of());

        WorkflowRecoveryService.RecoveryRunResult runResult = service.recoverRun(run).await().indefinitely();
        assertEquals(WorkflowReplayConsistencyChecker.Status.DRIFT, runResult.replayStatus());
        assertTrue(runResult.replayDrift());
        assertFalse(runResult.replayUnavailable());
        assertTrue(runResult.replayBlocked());
        assertEquals(WorkflowReplayConsistencyChecker.Status.DRIFT, runResult.replayBlockSummary().status());
        assertEquals(List.of("replay"), runResult.replayBlockSummary().mismatchFields());
        assertEquals(1, runResult.replayMismatches().size());
        assertEquals("replay", runResult.replayMismatches().getFirst().field());

        WorkflowRecoveryService.RecoverySweepResult result = service.recoverActiveRuns().await().indefinitely();

        assertEquals(1, result.scannedRuns());
        assertEquals(1, result.replayBlockedRuns());
        assertEquals(1, result.replayDrifts());
        assertEquals(1, result.replayBlockStatusCounts().get(WorkflowReplayConsistencyChecker.Status.DRIFT));
        assertEquals(1, result.replayBlockFieldCounts()
                .get(WorkflowReplayConsistencyChecker.Status.DRIFT)
                .get("replay"));
        assertEquals(1, result.replayMismatchCount());
        assertEquals(0, result.staleExecutions());
        assertEquals(0, repository.updateCount.get());
        assertEquals(NodeExecutionStatus.RUNNING, run.getNodeExecution(NODE_ID).getStatus());
    }

    @Test
    void recoverActiveRuns_skipsReplayValidationWhenModeDisabled() {
        NodeDefinition node = node(RetryPolicy.none(), Duration.ofSeconds(10));
        WorkflowDefinition definition = workflow(node);
        WorkflowRun run = WorkflowRun.create(TENANT, definition, Map.of());
        run.start();
        run.reserveNodeForDispatch(NODE_ID);
        run.getNodeExecution(NODE_ID).setStartedAt(NOW.minusSeconds(20));

        RecordingWorkflowRunRepository repository = new RecordingWorkflowRunRepository(run);
        WorkflowRecoveryService service = service(repository, new RecordingRetryManager());
        service.replayConsistencyMode = "disabled";
        service.definitionRepository = new RecordingWorkflowDefinitionRepository(definition);
        service.eventStore = new RecordingEventStore(List.of());

        WorkflowRecoveryService.RecoverySweepResult result = service.recoverActiveRuns().await().indefinitely();

        assertEquals(1, result.scannedRuns());
        assertEquals(0, result.replayBlockedRuns());
        assertEquals(0, result.replayDrifts());
        assertEquals(0, result.replayUnavailable());
        assertTrue(result.replayBlockStatusCounts().isEmpty());
        assertTrue(result.replayBlockFieldCounts().isEmpty());
        assertEquals(0, result.replayMismatchCount());
        assertEquals(1, result.staleExecutions());
        assertEquals(1, repository.updateCount.get());
        assertEquals(NodeExecutionStatus.FAILED, run.getNodeExecution(NODE_ID).getStatus());
    }

    @Test
    void recoverActiveRuns_requiresReplayDependenciesWhenModeStrict() {
        WorkflowRun run = runningRun(node(RetryPolicy.none(), Duration.ofSeconds(10)));
        run.reserveNodeForDispatch(NODE_ID);
        run.getNodeExecution(NODE_ID).setStartedAt(NOW.minusSeconds(20));

        RecordingWorkflowRunRepository repository = new RecordingWorkflowRunRepository(run);
        WorkflowRecoveryService service = service(repository, new RecordingRetryManager());
        service.replayConsistencyMode = "strict";
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        service.meterRegistry = meterRegistry;

        WorkflowRecoveryService.RecoveryRunResult runResult = service.recoverRun(run).await().indefinitely();
        assertEquals(WorkflowReplayConsistencyChecker.Status.UNAVAILABLE, runResult.replayStatus());
        assertFalse(runResult.replayDrift());
        assertTrue(runResult.replayUnavailable());
        assertTrue(runResult.replayBlocked());
        assertEquals(WorkflowReplayConsistencyChecker.Status.UNAVAILABLE, runResult.replayBlockSummary().status());
        assertEquals(List.of("replayConsistency"), runResult.replayBlockSummary().mismatchFields());
        assertEquals(1, runResult.replayMismatches().size());
        assertEquals("replayConsistency", runResult.replayMismatches().getFirst().field());
        assertTrue(String.valueOf(runResult.replayMismatches().getFirst().replayedValue()).contains("EventStore"));

        WorkflowRecoveryService.RecoverySweepResult result = service.recoverActiveRuns().await().indefinitely();

        assertEquals(1, result.scannedRuns());
        assertEquals(1, result.replayBlockedRuns());
        assertEquals(0, result.replayDrifts());
        assertEquals(1, result.replayUnavailable());
        assertEquals(1, result.replayBlockStatusCounts().get(WorkflowReplayConsistencyChecker.Status.UNAVAILABLE));
        assertEquals(1, result.replayBlockFieldCounts()
                .get(WorkflowReplayConsistencyChecker.Status.UNAVAILABLE)
                .get("replayConsistency"));
        assertEquals(1, result.replayMismatchCount());
        assertEquals(0, result.staleExecutions());
        assertEquals(0, repository.updateCount.get());
        assertEquals(NodeExecutionStatus.RUNNING, run.getNodeExecution(NODE_ID).getStatus());
        assertEquals(1.0, counter(meterRegistry, "gamelan.recovery.replay.unavailable"));
        assertEquals(1.0, counter(meterRegistry, "gamelan.recovery.replay.blocks", "status", "unavailable"));
    }

    @Test
    void recoveryRunResult_summarizesReplayBlockWithoutPayloadValues() {
        List<WorkflowReplayConsistencyChecker.Mismatch> mismatches = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            mismatches.add(new WorkflowReplayConsistencyChecker.Mismatch(
                    "field-" + i,
                    "snapshot-value-" + i,
                    "replayed-value-" + i));
        }
        mismatches.add(new WorkflowReplayConsistencyChecker.Mismatch(
                "field-1",
                "duplicate-snapshot-value",
                "duplicate-replayed-value"));
        WorkflowRecoveryService.RecoveryRunResult result = new WorkflowRecoveryService.RecoveryRunResult(
                null,
                null,
                1,
                0,
                0,
                0,
                false,
                false,
                WorkflowReplayConsistencyChecker.Status.DRIFT,
                mismatches,
                false);

        WorkflowRecoveryService.ReplayBlockSummary summary = result.replayBlockSummary();
        WorkflowRecoveryService.RecoverySweepResult sweepResult =
                WorkflowRecoveryService.RecoverySweepResult.fromRunResults(List.of(result));

        assertTrue(result.replayBlocked());
        assertEquals(WorkflowReplayConsistencyChecker.Status.DRIFT, summary.status());
        assertEquals(21, summary.mismatchCount());
        assertEquals(16, summary.mismatchFields().size());
        assertEquals("field-0", summary.mismatchFields().getFirst());
        assertEquals("field-15", summary.mismatchFields().getLast());
        assertTrue(summary.truncated());
        assertEquals(1, sweepResult.replayBlockFieldCounts()
                .get(WorkflowReplayConsistencyChecker.Status.DRIFT)
                .get("field-0"));
        assertFalse(sweepResult.replayBlockFieldCounts()
                .get(WorkflowReplayConsistencyChecker.Status.DRIFT)
                .containsKey("field-16"));
    }

    @Test
    void recoverActiveRuns_schedulesRetryWakeupWhenStaleExecutionWillRetry() {
        RetryPolicy retryPolicy = new RetryPolicy(2, Duration.ofSeconds(15), Duration.ofSeconds(15), 1.0, List.of());
        WorkflowRun run = runningRun(node(retryPolicy, Duration.ofSeconds(10)));
        run.reserveNodeForDispatch(NODE_ID);
        run.getNodeExecution(NODE_ID).setStartedAt(NOW.minusSeconds(20));

        RecordingRetryManager retryManager = new RecordingRetryManager();
        WorkflowRecoveryService service = service(new RecordingWorkflowRunRepository(run), retryManager);

        WorkflowRecoveryService.RecoverySweepResult result = service.recoverActiveRuns().await().indefinitely();

        assertEquals(1, result.staleExecutions());
        assertEquals(1, result.retryWakeups());
        assertEquals(NodeExecutionStatus.RETRYING, run.getNodeExecution(NODE_ID).getStatus());
        assertEquals(TENANT, retryManager.schedules.getFirst().tenantId());
        assertEquals(NODE_ID, retryManager.schedules.getFirst().nodeId());
        assertEquals(2, retryManager.schedules.getFirst().attempt());
        assertTrue(!retryManager.schedules.getFirst().delay().isNegative());
    }

    @Test
    void recoverActiveRuns_limitsConcurrentRunRecovery() throws Exception {
        WorkflowRun first = dueRetryRun();
        WorkflowRun second = dueRetryRun();
        WorkflowRun third = dueRetryRun();
        RecordingWorkflowRunRepository repository = new RecordingWorkflowRunRepository(List.of(first, second, third));
        CountDownLatch lockEntered = new CountDownLatch(2);
        CompletableFuture<Void> lockGate = new CompletableFuture<>();
        repository.blockLocks(lockEntered, lockGate);

        WorkflowRecoveryService service = service(repository, new RecordingRetryManager());
        service.maxConcurrentRuns = 2;

        CompletableFuture<WorkflowRecoveryService.RecoverySweepResult> sweep = service.recoverActiveRuns()
                .subscribeAsCompletionStage()
                .toCompletableFuture();

        assertTrue(lockEntered.await(2, TimeUnit.SECONDS));
        assertEquals(2, repository.activeLocks.get());
        assertEquals(2, repository.maxActiveLocks.get());
        assertFalse(sweep.isDone());

        lockGate.complete(null);
        WorkflowRecoveryService.RecoverySweepResult result = sweep.get(2, TimeUnit.SECONDS);

        assertEquals(3, result.scannedRuns());
        assertEquals(3, result.dueRetries());
        assertEquals(0, result.failedRuns());
        assertEquals(2, repository.maxActiveLocks.get());
    }

    @Test
    void recoverActiveRuns_skipsOverlappingInProcessSweep() throws Exception {
        WorkflowRun run = dueRetryRun();
        RecordingWorkflowRunRepository repository = new RecordingWorkflowRunRepository(run);
        CountDownLatch lockEntered = new CountDownLatch(1);
        CompletableFuture<Void> lockGate = new CompletableFuture<>();
        repository.blockLocks(lockEntered, lockGate);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

        WorkflowRecoveryService service = service(repository, new RecordingRetryManager());
        service.meterRegistry = meterRegistry;

        CompletableFuture<WorkflowRecoveryService.RecoverySweepResult> firstSweep = service.recoverActiveRuns()
                .subscribeAsCompletionStage()
                .toCompletableFuture();

        assertTrue(lockEntered.await(2, TimeUnit.SECONDS));

        WorkflowRecoveryService.RecoverySweepResult skippedSweep = service.recoverActiveRuns().await().indefinitely();

        assertEquals(0, skippedSweep.scannedRuns());
        assertEquals(1, skippedSweep.skippedSweeps());
        assertEquals(1, skippedSweep.skipReasonCounts()
                .get(WorkflowRecoveryService.RecoverySweepSkipReason.ALREADY_RUNNING));
        assertEquals(1, repository.recoveryQueryCount.get());
        assertEquals(1.0, counter(
                meterRegistry,
                "gamelan.recovery.sweeps.skipped",
                "reason",
                "already_running"));
        assertEquals(1L, meterRegistry.get("gamelan.recovery.sweep.skipped.duration")
                .tag("reason", "already_running")
                .timer()
                .count());

        lockGate.complete(null);
        WorkflowRecoveryService.RecoverySweepResult firstResult = firstSweep.get(2, TimeUnit.SECONDS);

        assertEquals(1, firstResult.scannedRuns());
        assertEquals(0, firstResult.skippedSweeps());
        assertTrue(firstResult.skipReasonCounts().isEmpty());
        assertEquals(1.0, counter(meterRegistry, "gamelan.recovery.sweeps", "outcome", "success"));
    }

    @Test
    void recoverActiveRuns_skipsWhenDistributedLeaseIsHeld() {
        WorkflowRun run = dueRetryRun();
        RecordingWorkflowRunRepository repository = new RecordingWorkflowRunRepository(run);
        RecordingRecoveryLeaseRepository leaseRepository = new RecordingRecoveryLeaseRepository(false);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        WorkflowRecoveryService service = service(repository, new RecordingRetryManager());
        service.distributedLeaseEnabled = true;
        service.recoveryLeaseRepository = leaseRepository;
        service.meterRegistry = meterRegistry;

        WorkflowRecoveryService.RecoverySweepResult result = service.recoverActiveRuns().await().indefinitely();

        assertEquals(0, result.scannedRuns());
        assertEquals(1, result.skippedSweeps());
        assertEquals(1, result.skipReasonCounts()
                .get(WorkflowRecoveryService.RecoverySweepSkipReason.LEASE_HELD));
        assertEquals(1, leaseRepository.acquireCount.get());
        assertEquals(0, leaseRepository.releaseCount.get());
        assertEquals(0, repository.recoveryQueryCount.get());
        assertEquals(1.0, counter(
                meterRegistry,
                "gamelan.recovery.sweeps.skipped",
                "reason",
                "lease_held"));
        assertEquals(1L, meterRegistry.get("gamelan.recovery.sweep.skipped.duration")
                .tag("reason", "lease_held")
                .timer()
                .count());
        assertEquals(1.0, leaseCounter(meterRegistry, "acquire", "held"));
    }

    @Test
    void recoverActiveRuns_skipsWhenDistributedLeaseRepositoryIsUnavailable() {
        WorkflowRun run = dueRetryRun();
        RecordingWorkflowRunRepository repository = new RecordingWorkflowRunRepository(run);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        WorkflowRecoveryService service = service(repository, new RecordingRetryManager());
        service.distributedLeaseEnabled = true;
        service.recoveryLeaseRepository = null;
        service.recoveryLeaseRepositories = null;
        service.meterRegistry = meterRegistry;

        WorkflowRecoveryService.RecoverySweepResult result = service.recoverActiveRuns().await().indefinitely();

        assertEquals(0, result.scannedRuns());
        assertEquals(1, result.skippedSweeps());
        assertEquals(1, result.skipReasonCounts()
                .get(WorkflowRecoveryService.RecoverySweepSkipReason.LEASE_REPOSITORY_UNAVAILABLE));
        assertEquals(0, repository.recoveryQueryCount.get());
        assertEquals(1.0, counter(
                meterRegistry,
                "gamelan.recovery.sweeps.skipped",
                "reason",
                "lease_repository_unavailable"));
        assertEquals(1L, meterRegistry.get("gamelan.recovery.sweep.skipped.duration")
                .tag("reason", "lease_repository_unavailable")
                .timer()
                .count());
        assertEquals(1.0, leaseCounter(meterRegistry, "acquire", "repository_unavailable"));
    }

    @Test
    void recoverActiveRuns_recordsDistributedLeaseAcquireFailureMetrics() {
        WorkflowRun run = dueRetryRun();
        RecordingWorkflowRunRepository repository = new RecordingWorkflowRunRepository(run);
        RecordingRecoveryLeaseRepository leaseRepository = new RecordingRecoveryLeaseRepository(true);
        leaseRepository.failAcquisitions(new IllegalStateException("lease store unavailable"));
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        WorkflowRecoveryService service = service(repository, new RecordingRetryManager());
        service.distributedLeaseEnabled = true;
        service.recoveryLeaseRepository = leaseRepository;
        service.meterRegistry = meterRegistry;

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> service.recoverActiveRuns().await().indefinitely());

        assertEquals("lease store unavailable", error.getMessage());
        assertEquals(1, leaseRepository.acquireCount.get());
        assertEquals(0, repository.recoveryQueryCount.get());
        assertEquals(1.0, counter(meterRegistry, "gamelan.recovery.sweeps", "outcome", "failure"));
        assertEquals(1L, meterRegistry.get("gamelan.recovery.sweep.duration")
                .tag("outcome", "failure")
                .timer()
                .count());
        assertEquals(1.0, leaseCounter(meterRegistry, "acquire", "failure"));
    }

    @Test
    void recoverActiveRuns_releasesDistributedLeaseAfterSweep() {
        WorkflowRun run = dueRetryRun();
        RecordingRecoveryLeaseRepository leaseRepository = new RecordingRecoveryLeaseRepository(true);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        WorkflowRecoveryService service = service(new RecordingWorkflowRunRepository(run), new RecordingRetryManager());
        service.distributedLeaseEnabled = true;
        service.recoveryLeaseRepository = leaseRepository;
        service.meterRegistry = meterRegistry;

        WorkflowRecoveryService.RecoverySweepResult result = service.recoverActiveRuns().await().indefinitely();

        assertEquals(1, result.scannedRuns());
        assertEquals(0, result.skippedSweeps());
        assertEquals(1, leaseRepository.acquireCount.get());
        assertEquals(1, leaseRepository.releaseCount.get());
        assertEquals(1.0, leaseCounter(meterRegistry, "acquire", "success"));
        assertEquals(1.0, leaseCounter(meterRegistry, "release", "success"));
    }

    @Test
    void recoverActiveRuns_usesConfiguredDistributedLeaseOwnerId() {
        WorkflowRun run = dueRetryRun();
        RecordingRecoveryLeaseRepository leaseRepository = new RecordingRecoveryLeaseRepository(true);
        WorkflowRecoveryService service = service(new RecordingWorkflowRunRepository(run), new RecordingRetryManager());
        service.distributedLeaseEnabled = true;
        service.distributedLeaseOwnerId = " configured-owner ";
        service.engineId = "engine-a";
        service.recoveryLeaseRepository = leaseRepository;

        service.recoverActiveRuns().await().indefinitely();

        assertEquals(List.of("configured-owner"), leaseRepository.acquireOwnerIds);
    }

    @Test
    void recoverActiveRuns_usesEngineIdAsDistributedLeaseOwnerFallback() {
        WorkflowRun run = dueRetryRun();
        RecordingRecoveryLeaseRepository leaseRepository = new RecordingRecoveryLeaseRepository(true);
        WorkflowRecoveryService service = service(new RecordingWorkflowRunRepository(run), new RecordingRetryManager());
        service.distributedLeaseEnabled = true;
        service.distributedLeaseOwnerId = " ";
        service.engineId = "engine-a";
        service.recoveryLeaseRepository = leaseRepository;

        service.recoverActiveRuns().await().indefinitely();

        assertEquals(List.of("gamelan-recovery-engine-a"), leaseRepository.acquireOwnerIds);
    }

    @Test
    void recoverActiveRuns_renewsDistributedLeaseDuringLongSweep() {
        WorkflowRun run = dueRetryRun();
        MutableClock clock = new MutableClock(NOW);
        RecordingWorkflowRunRepository repository = new RecordingWorkflowRunRepository(run);
        repository.onRecoveryScan(() -> clock.advance(Duration.ofSeconds(5)));
        RecordingRecoveryLeaseRepository leaseRepository = new RecordingRecoveryLeaseRepository(true);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        WorkflowRecoveryService service = service(repository, new RecordingRetryManager());
        service.clock = clock;
        service.distributedLeaseEnabled = true;
        service.distributedLeaseTtl = Duration.ofSeconds(10);
        service.distributedLeaseRenewInterval = Duration.ofSeconds(5);
        service.recoveryLeaseRepository = leaseRepository;
        service.meterRegistry = meterRegistry;

        WorkflowRecoveryService.RecoverySweepResult result = service.recoverActiveRuns().await().indefinitely();

        assertEquals(1, result.scannedRuns());
        assertEquals(0, result.failedRuns());
        assertEquals(2, leaseRepository.acquireCount.get());
        assertEquals(List.of(NOW, NOW.plusSeconds(5)), leaseRepository.acquireInstants);
        assertEquals(1, leaseRepository.releaseCount.get());
        assertEquals(1.0, leaseCounter(meterRegistry, "acquire", "success"));
        assertEquals(1.0, leaseCounter(meterRegistry, "renew", "success"));
        assertEquals(1.0, leaseCounter(meterRegistry, "release", "success"));
    }

    @Test
    void recoverActiveRuns_failsSweepWhenDistributedLeaseRenewalIsLost() {
        WorkflowRun run = dueRetryRun();
        MutableClock clock = new MutableClock(NOW);
        RecordingWorkflowRunRepository repository = new RecordingWorkflowRunRepository(run);
        repository.onRecoveryScan(() -> clock.advance(Duration.ofSeconds(5)));
        RecordingRecoveryLeaseRepository leaseRepository = new RecordingRecoveryLeaseRepository(true);
        leaseRepository.rejectRenewals();
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        WorkflowRecoveryService service = service(repository, new RecordingRetryManager());
        service.clock = clock;
        service.distributedLeaseEnabled = true;
        service.distributedLeaseTtl = Duration.ofSeconds(10);
        service.distributedLeaseRenewInterval = Duration.ofSeconds(5);
        service.recoveryLeaseRepository = leaseRepository;
        service.meterRegistry = meterRegistry;

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> service.recoverActiveRuns().await().indefinitely());

        assertTrue(error.getMessage().contains("lease_held"));
        assertEquals(2, leaseRepository.acquireCount.get());
        assertEquals(1, leaseRepository.releaseCount.get());
        assertEquals(0, repository.lockCount.get());
        assertEquals(1.0, counter(meterRegistry, "gamelan.recovery.sweeps", "outcome", "failure"));
        assertEquals(1.0, leaseCounter(meterRegistry, "acquire", "success"));
        assertEquals(1.0, leaseCounter(meterRegistry, "renew", "held"));
        assertEquals(1.0, leaseCounter(meterRegistry, "release", "success"));
    }

    @Test
    void recoverActiveRuns_continuesWhenOneRunRecoveryFails() {
        WorkflowRun failedRun = runningRun(node(RetryPolicy.none(), Duration.ZERO));
        WorkflowRun healthyRun = dueRetryRun();
        RecordingWorkflowRunRepository repository = new RecordingWorkflowRunRepository(List.of(failedRun, healthyRun));
        repository.failLockFor(failedRun.getId());
        WorkflowRecoveryService service = service(repository, new RecordingRetryManager());

        WorkflowRecoveryService.RecoverySweepResult result = service.recoverActiveRuns().await().indefinitely();

        assertEquals(2, result.scannedRuns());
        assertEquals(1, result.failedRuns());
        assertEquals(1, result.dueRetries());
        assertEquals(1, result.wakeups());
    }

    @Test
    void recoverActiveRuns_collectsStablePageSnapshotBeforeMutatingRuns() {
        WorkflowRun terminalAfterRecovery = runningRun(node(RetryPolicy.none(), Duration.ofSeconds(10), true));
        terminalAfterRecovery.reserveNodeForDispatch(NODE_ID);
        terminalAfterRecovery.getNodeExecution(NODE_ID).setStartedAt(NOW.minusSeconds(20));
        WorkflowRun dueRetry = dueRetryRun();
        RecordingWorkflowRunRepository repository = new RecordingWorkflowRunRepository(
                List.of(terminalAfterRecovery, dueRetry));
        WorkflowRecoveryService service = service(repository, new RecordingRetryManager());
        service.pageSize = 1;

        WorkflowRecoveryService.RecoverySweepResult result = service.recoverActiveRuns().await().indefinitely();

        assertEquals(2, result.scannedRuns());
        assertEquals(1, result.staleExecutions());
        assertEquals(1, result.dueRetries());
        assertEquals(RunStatus.FAILED, terminalAfterRecovery.getStatus());
    }

    @Test
    void recoverActiveRuns_deduplicatesRunsThatAppearOnMultipleRecoveryPages() {
        WorkflowRun duplicate = dueRetryRun();
        WorkflowRun next = dueRetryRun();
        RecordingWakeupPublisher wakeupPublisher = new RecordingWakeupPublisher();
        RecordingWorkflowRunRepository repository = new RecordingWorkflowRunRepository(List.of(duplicate, next));
        repository.recoveryPages(List.of(List.of(duplicate), List.of(duplicate), List.of(next)));
        WorkflowRecoveryService service = service(repository, new RecordingRetryManager());
        service.pageSize = 1;
        service.wakeupPublisher = wakeupPublisher;

        WorkflowRecoveryService.RecoverySweepResult result = service.recoverActiveRuns().await().indefinitely();

        assertEquals(2, result.scannedRuns());
        assertEquals(2, result.dueRetries());
        assertEquals(2, result.wakeups());
        assertEquals(4, result.scanPages());
        assertEquals(3, result.scanCandidates());
        assertEquals(1, result.duplicateScanCandidates());
        assertEquals(0, result.invalidScanCandidates());
        assertEquals(0, result.scanCursorStalls());
        assertEquals(2, repository.lockCount.get());
        assertEquals(2, wakeupPublisher.events.size());
    }

    @Test
    void recoverActiveRuns_stopsAtConfiguredScanLimit() {
        RecordingWorkflowRunRepository repository = new RecordingWorkflowRunRepository(List.of(
                dueRetryRun(),
                dueRetryRun(),
                dueRetryRun(),
                dueRetryRun(),
                dueRetryRun()));
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        WorkflowRecoveryService service = service(repository, new RecordingRetryManager());
        service.pageSize = 2;
        service.maxScanRuns = 3;
        service.meterRegistry = meterRegistry;

        WorkflowRecoveryService.RecoverySweepResult result = service.recoverActiveRuns().await().indefinitely();

        assertEquals(3, result.scannedRuns());
        assertEquals(3, result.dueRetries());
        assertEquals(3, result.wakeups());
        assertEquals(2, result.scanPages());
        assertEquals(3, result.scanCandidates());
        assertEquals(0, result.duplicateScanCandidates());
        assertEquals(3, repository.lockCount.get());
        assertTrue(result.scanLimitReached());
        assertEquals(1.0, counter(meterRegistry, "gamelan.recovery.scan.limit_reached"));
    }

    @Test
    void recoverActiveRuns_countsDuplicateRowsTowardConfiguredScanLimit() {
        WorkflowRun duplicate = dueRetryRun();
        WorkflowRun next = dueRetryRun();
        RecordingWorkflowRunRepository repository = new RecordingWorkflowRunRepository(List.of(duplicate, next));
        repository.recoveryPages(List.of(List.of(duplicate), List.of(duplicate), List.of(duplicate), List.of(next)));
        WorkflowRecoveryService service = service(repository, new RecordingRetryManager());
        service.pageSize = 1;
        service.maxScanRuns = 2;

        WorkflowRecoveryService.RecoverySweepResult result = service.recoverActiveRuns().await().indefinitely();

        assertEquals(1, result.scannedRuns());
        assertEquals(1, result.dueRetries());
        assertEquals(1, result.wakeups());
        assertEquals(2, result.scanPages());
        assertEquals(2, result.scanCandidates());
        assertEquals(1, result.duplicateScanCandidates());
        assertEquals(1, repository.lockCount.get());
        assertEquals(2, repository.recoveryQueryCount.get());
        assertTrue(result.scanLimitReached());
    }

    @Test
    void recoverActiveRuns_reportsCursorStallsWithoutMarkingScanLimitReached() {
        WorkflowRun run = dueRetryRun();
        RecordingWorkflowRunRepository repository = new RecordingWorkflowRunRepository(run);
        repository.recoveryScanPages(List.of(new WorkflowRunRecoveryPage(
                List.of(run),
                WorkflowRunRecoveryCursor.start(),
                true)));
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        WorkflowRecoveryService service = service(repository, new RecordingRetryManager());
        service.meterRegistry = meterRegistry;

        WorkflowRecoveryService.RecoverySweepResult result = service.recoverActiveRuns().await().indefinitely();

        assertEquals(1, result.scannedRuns());
        assertEquals(1, result.scanPages());
        assertEquals(1, result.scanCandidates());
        assertEquals(1, result.scanCursorStalls());
        assertFalse(result.scanLimitReached());
        assertEquals(1.0, counter(meterRegistry, "gamelan.recovery.scan.cursor_stalls"));
        assertEquals(0.0, counter(meterRegistry, "gamelan.recovery.scan.limit_reached"));
    }

    @Test
    void recoverActiveRuns_reportsNoWorkRuns() {
        WorkflowRun run = runningRun(node(RetryPolicy.none(), Duration.ZERO));
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        WorkflowRecoveryService service = service(new RecordingWorkflowRunRepository(run), new RecordingRetryManager());
        service.meterRegistry = meterRegistry;

        WorkflowRecoveryService.RecoverySweepResult result = service.recoverActiveRuns().await().indefinitely();

        assertEquals(1, result.scannedRuns());
        assertEquals(1, result.noWorkRuns());
        assertEquals(0, result.deferredActiveRuns());
        assertEquals(0, result.skippedAfterLockRuns());
        assertEquals(0, result.dueRetries());
        assertEquals(0, result.staleExecutions());
        assertEquals(0, result.wakeups());
        assertEquals(1, result.lockedOutcomeStatusCounts()
                .get(WorkflowRecoveryService.LockedRecoveryOutcome.NO_WORK)
                .get(RunStatus.RUNNING));
        assertEquals(1.0, counter(meterRegistry, "gamelan.recovery.runs.no_work"));
        assertEquals(1.0, counter(
                meterRegistry,
                "gamelan.recovery.runs.locked_status",
                "outcome",
                "no_work",
                "status",
                "running"));
        assertEquals(0.0, counter(meterRegistry, "gamelan.recovery.runs.deferred_active"));
        assertEquals(0.0, counter(meterRegistry, "gamelan.recovery.runs.skipped_after_lock"));
    }

    @Test
    void recoverActiveRuns_reportsDeferredActiveRuns() {
        WorkflowRun run = runningRun(node(RetryPolicy.none(), Duration.ZERO));
        run.suspend("waiting for signal", NODE_ID);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        WorkflowRecoveryService service = service(new RecordingWorkflowRunRepository(run), new RecordingRetryManager());
        service.meterRegistry = meterRegistry;

        WorkflowRecoveryService.RecoverySweepResult result = service.recoverActiveRuns().await().indefinitely();

        assertEquals(1, result.scannedRuns());
        assertEquals(0, result.noWorkRuns());
        assertEquals(1, result.deferredActiveRuns());
        assertEquals(0, result.skippedAfterLockRuns());
        assertEquals(0, result.wakeups());
        assertEquals(1, result.lockedOutcomeStatusCounts()
                .get(WorkflowRecoveryService.LockedRecoveryOutcome.DEFERRED_ACTIVE)
                .get(RunStatus.SUSPENDED));
        assertEquals(1.0, counter(meterRegistry, "gamelan.recovery.runs.deferred_active"));
        assertEquals(1.0, counter(
                meterRegistry,
                "gamelan.recovery.runs.locked_status",
                "outcome",
                "deferred_active",
                "status",
                "suspended"));
        assertEquals(0.0, counter(meterRegistry, "gamelan.recovery.runs.skipped_after_lock"));
    }

    @Test
    void recoverActiveRuns_reportsSkippedAfterLockRuns() {
        NodeDefinition node = node(RetryPolicy.none(), Duration.ZERO);
        WorkflowDefinition definition = workflow(node);
        WorkflowRun lockedRun = WorkflowRun.create(TENANT, definition, Map.of());
        lockedRun.start();
        WorkflowRun scannedRun = WorkflowRun.restore(lockedRun.createSnapshot(), definition);
        lockedRun.cancel("completed by another engine");
        RecordingWorkflowRunRepository repository = new RecordingWorkflowRunRepository(lockedRun);
        repository.recoveryScanPages(List.of(new WorkflowRunRecoveryPage(
                List.of(scannedRun),
                WorkflowRunRecoveryCursor.start(),
                false)));
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        WorkflowRecoveryService service = service(repository, new RecordingRetryManager());
        service.meterRegistry = meterRegistry;

        WorkflowRecoveryService.RecoverySweepResult result = service.recoverActiveRuns().await().indefinitely();

        assertEquals(1, result.scannedRuns());
        assertEquals(0, result.noWorkRuns());
        assertEquals(0, result.deferredActiveRuns());
        assertEquals(1, result.skippedAfterLockRuns());
        assertEquals(0, result.wakeups());
        assertEquals(0, repository.updateCount.get());
        assertEquals(1, result.lockedOutcomeStatusCounts()
                .get(WorkflowRecoveryService.LockedRecoveryOutcome.SKIPPED_AFTER_LOCK)
                .get(RunStatus.CANCELLED));
        assertEquals(1.0, counter(meterRegistry, "gamelan.recovery.runs.skipped_after_lock"));
        assertEquals(1.0, counter(
                meterRegistry,
                "gamelan.recovery.runs.locked_status",
                "outcome",
                "skipped_after_lock",
                "status",
                "cancelled"));
    }

    @Test
    void recoverActiveRuns_recordsRecoveryMetrics() {
        WorkflowRun staleRun = runningRun(node(RetryPolicy.none(), Duration.ofSeconds(10)));
        staleRun.reserveNodeForDispatch(NODE_ID);
        staleRun.getNodeExecution(NODE_ID).setStartedAt(NOW.minusSeconds(20));
        WorkflowRun dueRetry = dueRetryRun();
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        WorkflowRecoveryService service = service(
                new RecordingWorkflowRunRepository(List.of(staleRun, dueRetry)),
                new RecordingRetryManager());
        service.meterRegistry = meterRegistry;

        WorkflowRecoveryService.RecoverySweepResult result = service.recoverActiveRuns().await().indefinitely();

        assertEquals(2, result.scannedRuns());
        assertEquals(1.0, counter(meterRegistry, "gamelan.recovery.sweeps", "outcome", "success"));
        assertEquals(2.0, counter(meterRegistry, "gamelan.recovery.runs.scanned"));
        assertEquals(1.0, counter(meterRegistry, "gamelan.recovery.retries.due"));
        assertEquals(1.0, counter(meterRegistry, "gamelan.recovery.executions.stale_recovered"));
        assertEquals(1.0, counter(meterRegistry, "gamelan.recovery.runs.mutated"));
        assertEquals(0.0, counter(meterRegistry, "gamelan.recovery.runs.no_work"));
        assertEquals(0.0, counter(meterRegistry, "gamelan.recovery.runs.deferred_active"));
        assertEquals(0.0, counter(meterRegistry, "gamelan.recovery.runs.skipped_after_lock"));
        assertEquals(0.0, counter(
                meterRegistry,
                "gamelan.recovery.runs.locked_status",
                "outcome",
                "deferred_active",
                "status",
                "suspended"));
        assertEquals(0.0, counter(meterRegistry, "gamelan.recovery.replay.blocks", "status", "drift"));
        assertEquals(2.0, counter(meterRegistry, "gamelan.recovery.wakeups.requested"));
        assertEquals(1.0, counter(meterRegistry, "gamelan.recovery.scan.pages"));
        assertEquals(2.0, counter(meterRegistry, "gamelan.recovery.scan.candidates"));
        assertEquals(0.0, counter(meterRegistry, "gamelan.recovery.scan.duplicates"));
        assertEquals(0.0, counter(meterRegistry, "gamelan.recovery.scan.invalid_candidates"));
        assertEquals(0.0, counter(meterRegistry, "gamelan.recovery.scan.cursor_stalls"));
        assertEquals(1L, meterRegistry.get("gamelan.recovery.sweep.duration")
                .tag("outcome", "success")
                .timer()
                .count());
    }

    @Test
    void recoverActiveRuns_recordsFailedSweepMetrics() {
        RecordingWorkflowRunRepository repository = new RecordingWorkflowRunRepository(List.of());
        repository.failRecoveryQuery(new IllegalStateException("query unavailable"));
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        WorkflowRecoveryService service = service(repository, new RecordingRetryManager());
        service.meterRegistry = meterRegistry;

        assertThrows(IllegalStateException.class, () -> service.recoverActiveRuns().await().indefinitely());

        assertEquals(1.0, counter(meterRegistry, "gamelan.recovery.sweeps", "outcome", "failure"));
        assertEquals(1L, meterRegistry.get("gamelan.recovery.sweep.duration")
                .tag("outcome", "failure")
                .timer()
                .count());
    }

    private CountDownLatch wakeupLatch(WorkflowRunId runId) {
        CountDownLatch latch = new CountDownLatch(1);
        vertx.eventBus().<Object>consumer(WorkflowRecoveryService.RUN_UPDATED_ADDRESS)
                .handler(message -> {
                    Object body = message.body();
                    String eventRunId = body instanceof JsonObject json
                            ? json.getString("runId")
                            : String.valueOf(body);
                    if (runId.value().equals(eventRunId)) {
                        latch.countDown();
                    }
                });
        return latch;
    }

    private WorkflowRecoveryService service(
            RecordingWorkflowRunRepository repository,
            RecordingRetryManager retryManager) {
        WorkflowRecoveryService service = new WorkflowRecoveryService();
        service.runRepository = repository;
        service.eventBus = vertx.eventBus();
        service.clock = new FixedClock(NOW);
        service.retryManager = retryManager;
        service.analyzer = new WorkflowRecoveryAnalyzer();
        service.enabled = true;
        service.pageSize = 10;
        service.timeoutGrace = Duration.ZERO;
        return service;
    }

    private static double counter(SimpleMeterRegistry meterRegistry, String name, String... tags) {
        return meterRegistry.get(name).tags(tags).counter().count();
    }

    private static double leaseCounter(SimpleMeterRegistry meterRegistry, String operation, String outcome) {
        return counter(
                meterRegistry,
                "gamelan.recovery.lease.operations",
                "operation",
                operation,
                "outcome",
                outcome);
    }

    private static WorkflowRun runningRun(NodeDefinition node) {
        WorkflowDefinition definition = workflow(node);
        WorkflowRun run = WorkflowRun.create(TENANT, definition, Map.of());
        run.start();
        return run;
    }

    private static WorkflowRun dueRetryRun() {
        WorkflowRun run = runningRun(node(RetryPolicy.DEFAULT, Duration.ZERO));
        run.reserveNodeForDispatch(NODE_ID);
        run.failNode(NODE_ID, 1, error());
        run.getNodeExecution(NODE_ID).setRetryAt(NOW.minusSeconds(1));
        return run;
    }

    private static NodeDefinition node(RetryPolicy retryPolicy, Duration timeout) {
        return node(retryPolicy, timeout, false);
    }

    private static NodeDefinition node(RetryPolicy retryPolicy, Duration timeout, boolean critical) {
        return new NodeDefinition(
                NODE_ID,
                "node-1",
                NodeType.TASK,
                "local",
                Map.of(),
                List.of(),
                List.of(),
                retryPolicy,
                timeout,
                critical);
    }

    private static WorkflowDefinition workflow(NodeDefinition node) {
        return new WorkflowDefinition(
                WorkflowDefinitionId.of("wf-1"),
                TENANT,
                "test-workflow",
                "1.0.0",
                null,
                WorkflowMode.FLOW,
                List.of(node),
                Map.of(),
                Map.of(),
                null,
                RetryPolicy.none(),
                CompensationPolicy.disabled());
    }

    private static tech.kayys.gamelan.engine.error.ErrorInfo error() {
        return new tech.kayys.gamelan.engine.error.ErrorInfo("TEST_ERROR", "boom", "", Map.of());
    }

    private static final class FixedClock extends SystemClock {
        private final Instant now;

        private FixedClock(Instant now) {
            this.now = now;
        }

        @Override
        public Instant now() {
            return now;
        }
    }

    private static final class MutableClock extends SystemClock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public Instant now() {
            return now;
        }

        private void advance(Duration duration) {
            now = now.plus(duration);
        }
    }

    private static final class RecordingRetryManager implements RetryManager {
        private final List<RetrySchedule> schedules = new ArrayList<>();

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

    private record RetrySchedule(WorkflowRunId runId, TenantId tenantId, NodeId nodeId, int attempt, Duration delay) {
    }

    private static final class RecordingWakeupPublisher implements WorkflowRunWakeupPublisher {
        private final List<WorkflowRunUpdateEvent> events = new ArrayList<>();
        private RuntimeException failure;

        private void failWith(RuntimeException failure) {
            this.failure = failure;
        }

        @Override
        public Uni<Void> publish(WorkflowRunUpdateEvent event) {
            events.add(event);
            if (failure != null) {
                return Uni.createFrom().failure(failure);
            }
            return Uni.createFrom().voidItem();
        }
    }

    private static final class RecordingEventStore implements EventStore {
        private final List<ExecutionEvent> events;

        private RecordingEventStore(List<ExecutionEvent> events) {
            this.events = events != null ? List.copyOf(events) : List.of();
        }

        @Override
        public Uni<Void> appendEvents(WorkflowRunId runId, List<ExecutionEvent> events, long expectedVersion) {
            return Uni.createFrom().voidItem();
        }

        @Override
        public Uni<List<ExecutionEvent>> getEvents(WorkflowRunId runId) {
            return Uni.createFrom().item(events);
        }

        @Override
        public Uni<List<ExecutionEvent>> getEvents(WorkflowRunId runId, TenantId tenantId) {
            return Uni.createFrom().item(events);
        }

        @Override
        public Uni<List<ExecutionEvent>> getEventsAfterVersion(WorkflowRunId runId, long afterVersion) {
            return Uni.createFrom().item(events);
        }

        @Override
        public Uni<List<ExecutionEvent>> getEventsByType(WorkflowRunId runId, String eventType) {
            return Uni.createFrom().item(events.stream()
                    .filter(event -> eventType.equals(event.eventType()))
                    .toList());
        }
    }

    private static final class RecordingWorkflowDefinitionRepository implements WorkflowDefinitionRepository {
        private final WorkflowDefinition definition;

        private RecordingWorkflowDefinitionRepository(WorkflowDefinition definition) {
            this.definition = definition;
        }

        @Override
        public Uni<WorkflowDefinition> findById(WorkflowDefinitionId id, TenantId tenantId) {
            boolean matches = definition != null
                    && definition.id().equals(id)
                    && definition.tenantId().equals(tenantId);
            return Uni.createFrom().item(matches ? definition : null);
        }

        @Override
        public Uni<WorkflowDefinition> save(WorkflowDefinition definition, TenantId tenantId) {
            return Uni.createFrom().item(definition);
        }

        @Override
        public Uni<List<WorkflowDefinition>> findByTenant(TenantId tenantId, boolean activeOnly) {
            return Uni.createFrom().item(definition != null && definition.tenantId().equals(tenantId)
                    ? List.of(definition)
                    : List.of());
        }

        @Override
        public Uni<WorkflowDefinition> findByName(String name, TenantId tenantId) {
            boolean matches = definition != null
                    && definition.name().equals(name)
                    && definition.tenantId().equals(tenantId);
            return Uni.createFrom().item(matches ? definition : null);
        }

        @Override
        public Uni<Void> delete(WorkflowDefinitionId id, TenantId tenantId) {
            return Uni.createFrom().voidItem();
        }
    }

    private static final class RecordingRecoveryLeaseRepository implements WorkflowRecoveryLeaseRepository {
        private final boolean acquire;
        private final AtomicInteger acquireCount = new AtomicInteger();
        private final AtomicInteger releaseCount = new AtomicInteger();
        private final List<Instant> acquireInstants = new ArrayList<>();
        private final List<String> acquireOwnerIds = new ArrayList<>();
        private boolean rejectRenewals;
        private RuntimeException acquisitionFailure;

        private RecordingRecoveryLeaseRepository(boolean acquire) {
            this.acquire = acquire;
        }

        private void rejectRenewals() {
            this.rejectRenewals = true;
        }

        private void failAcquisitions(RuntimeException acquisitionFailure) {
            this.acquisitionFailure = acquisitionFailure;
        }

        @Override
        public Uni<WorkflowRecoveryLease> tryAcquireRecoveryLease(
                String leaseName,
                String ownerId,
                Duration ttl,
                Instant now) {
            int attempt = acquireCount.incrementAndGet();
            acquireInstants.add(now);
            acquireOwnerIds.add(ownerId);
            if (acquisitionFailure != null) {
                return Uni.createFrom().failure(acquisitionFailure);
            }
            if (!acquire || (rejectRenewals && attempt > 1)) {
                return Uni.createFrom().item(WorkflowRecoveryLease.notAcquired(leaseName, ownerId));
            }
            Instant acquiredAt = now != null ? now : NOW;
            Duration safeTtl = ttl != null && !ttl.isZero() && !ttl.isNegative() ? ttl : Duration.ofMinutes(2);
            return Uni.createFrom().item(WorkflowRecoveryLease.acquired(
                    leaseName,
                    ownerId,
                    acquiredAt,
                    acquiredAt.plus(safeTtl)));
        }

        @Override
        public Uni<Void> releaseRecoveryLease(WorkflowRecoveryLease lease) {
            releaseCount.incrementAndGet();
            return Uni.createFrom().voidItem();
        }
    }

    private static final class RecordingWorkflowRunRepository implements WorkflowRunRepository {
        private final Map<WorkflowRunId, WorkflowRun> runs = new LinkedHashMap<>();
        private final Set<WorkflowRunId> failingLocks = new HashSet<>();
        private final AtomicInteger updateCount = new AtomicInteger();
        private final AtomicInteger lockCount = new AtomicInteger();
        private final AtomicInteger activeLocks = new AtomicInteger();
        private final AtomicInteger maxActiveLocks = new AtomicInteger();
        private final AtomicInteger recoveryQueryCount = new AtomicInteger();
        private List<List<WorkflowRun>> recoveryPages;
        private List<WorkflowRunRecoveryPage> recoveryScanPages;
        private RuntimeException queryFailure;
        private CountDownLatch lockEntered;
        private CompletableFuture<Void> lockGate;
        private Runnable recoveryScanHook;

        private RecordingWorkflowRunRepository(WorkflowRun run) {
            this(List.of(run));
        }

        private RecordingWorkflowRunRepository(List<WorkflowRun> runs) {
            for (WorkflowRun run : runs) {
                this.runs.put(run.getId(), run);
            }
        }

        private void blockLocks(CountDownLatch lockEntered, CompletableFuture<Void> lockGate) {
            this.lockEntered = lockEntered;
            this.lockGate = lockGate;
        }

        private void failLockFor(WorkflowRunId runId) {
            failingLocks.add(runId);
        }

        private void recoveryPages(List<List<WorkflowRun>> recoveryPages) {
            this.recoveryPages = recoveryPages;
        }

        private void recoveryScanPages(List<WorkflowRunRecoveryPage> recoveryScanPages) {
            this.recoveryScanPages = recoveryScanPages;
        }

        private void failRecoveryQuery(RuntimeException queryFailure) {
            this.queryFailure = queryFailure;
        }

        private void onRecoveryScan(Runnable recoveryScanHook) {
            this.recoveryScanHook = recoveryScanHook;
        }

        @Override
        public Uni<WorkflowRun> persist(WorkflowRun run) {
            runs.put(run.getId(), run);
            return Uni.createFrom().item(run);
        }

        @Override
        public Uni<WorkflowRun> update(WorkflowRun run) {
            updateCount.incrementAndGet();
            runs.put(run.getId(), run);
            return Uni.createFrom().item(run);
        }

        @Override
        public Uni<WorkflowRun> findById(WorkflowRunId id) {
            return Uni.createFrom().item(runs.get(id));
        }

        @Override
        public Uni<WorkflowRun> findById(WorkflowRunId id, TenantId tenantId) {
            WorkflowRun run = runs.get(id);
            return Uni.createFrom().item(run != null && tenantId.equals(run.getTenantId()) ? run : null);
        }

        @Override
        public <T> Uni<T> withLock(WorkflowRunId runId, Function<WorkflowRun, Uni<T>> action) {
            WorkflowRun run = runs.get(runId);
            if (run == null) {
                return Uni.createFrom().failure(new NoSuchElementException(runId.value()));
            }
            if (failingLocks.contains(runId)) {
                return Uni.createFrom().failure(new IllegalStateException("lock failed for " + runId.value()));
            }
            lockCount.incrementAndGet();
            int active = activeLocks.incrementAndGet();
            maxActiveLocks.accumulateAndGet(active, Math::max);
            if (lockEntered != null) {
                lockEntered.countDown();
            }
            CompletableFuture<Void> gate = lockGate;
            Uni<Void> gateUni = gate != null
                    ? Uni.createFrom().completionStage(gate).replaceWithVoid()
                    : Uni.createFrom().voidItem();
            return gateUni
                    .flatMap(ignored -> {
                        try {
                            return action.apply(run);
                        } catch (RuntimeException error) {
                            return Uni.createFrom().failure(error);
                        }
                    })
                    .onItemOrFailure().invoke((ignored, failure) -> activeLocks.decrementAndGet());
        }

        @Override
        public Uni<WorkflowRunSnapshot> snapshot(WorkflowRunId runId, TenantId tenantId) {
            WorkflowRun run = runs.get(runId);
            return Uni.createFrom().item(run != null ? run.createSnapshot() : null);
        }

        @Override
        public Uni<List<WorkflowRun>> query(
                TenantId tenantId,
                WorkflowDefinitionId definitionId,
                RunStatus status,
                int page,
                int size) {
            return Uni.createFrom().item(runs.values().stream()
                    .filter(run -> tenantId == null || tenantId.equals(run.getTenantId()))
                    .filter(run -> definitionId == null || definitionId.equals(run.getDefinitionId()))
                    .filter(run -> status == null || status == run.getStatus())
                    .toList());
        }

        @Override
        public Uni<List<WorkflowRun>> queryActiveRunsForRecovery(int page, int size) {
            recoveryQueryCount.incrementAndGet();
            if (queryFailure != null) {
                return Uni.createFrom().failure(queryFailure);
            }
            if (recoveryPages != null) {
                return Uni.createFrom().item(page < recoveryPages.size() ? recoveryPages.get(page) : List.of());
            }
            List<WorkflowRun> activeRuns = runs.values().stream()
                    .filter(run -> run.getStatus().isActive())
                    .toList();
            int from = page * size;
            if (from >= activeRuns.size()) {
                return Uni.createFrom().item(List.of());
            }
            int to = Math.min(from + size, activeRuns.size());
            return Uni.createFrom().item(List.copyOf(activeRuns.subList(from, to)));
        }

        @Override
        public Uni<WorkflowRunRecoveryPage> scanActiveRunsForRecovery(WorkflowRunRecoveryCursor cursor, int size) {
            int queryIndex = recoveryQueryCount.getAndIncrement();
            if (queryFailure != null) {
                return Uni.createFrom().failure(queryFailure);
            }
            if (recoveryScanHook != null) {
                recoveryScanHook.run();
            }
            WorkflowRunRecoveryCursor safeCursor = cursor != null ? cursor : WorkflowRunRecoveryCursor.start();
            int safeSize = size > 0 ? size : 100;
            if (recoveryScanPages != null) {
                return Uni.createFrom().item(queryIndex < recoveryScanPages.size()
                        ? recoveryScanPages.get(queryIndex)
                        : new WorkflowRunRecoveryPage(List.of(), safeCursor, false));
            }
            List<WorkflowRun> page;
            if (recoveryPages != null) {
                page = safeCursor.page() < recoveryPages.size() ? recoveryPages.get(safeCursor.page()) : List.of();
            } else {
                List<WorkflowRun> activeRuns = runs.values().stream()
                        .filter(run -> run.getStatus().isActive())
                        .toList();
                int from = safeCursor.page() * safeSize;
                if (from >= activeRuns.size()) {
                    page = List.of();
                } else {
                    int to = Math.min(from + safeSize, activeRuns.size());
                    page = List.copyOf(activeRuns.subList(from, to));
                }
            }
            return Uni.createFrom().item(WorkflowRunRecoveryPage.offset(page, safeCursor, safeSize));
        }

        @Override
        public Uni<Long> countActiveRuns(TenantId tenantId) {
            return Uni.createFrom().item(runs.values().stream()
                    .filter(run -> tenantId == null || tenantId.equals(run.getTenantId()))
                    .filter(run -> run.getStatus().isActive())
                    .count());
        }

        @Override
        public Uni<Void> storeToken(ExecutionToken token) {
            return Uni.createFrom().voidItem();
        }

        @Override
        public Uni<Boolean> validateToken(ExecutionToken token) {
            return Uni.createFrom().item(true);
        }

        @Override
        public Uni<Void> storeCallback(CallbackRegistration callback) {
            return Uni.createFrom().voidItem();
        }

        @Override
        public Uni<Boolean> validateCallback(WorkflowRunId runId, String token) {
            return Uni.createFrom().item(true);
        }

        @Override
        public Uni<Void> updateContextVariable(WorkflowRunId runId, String key, Object value) {
            return Uni.createFrom().voidItem();
        }

        @Override
        public Uni<Void> updateNodeExecution(WorkflowRunId runId, NodeId nodeId, NodeExecutionSnapshot snapshot) {
            return Uni.createFrom().voidItem();
        }
    }
}
