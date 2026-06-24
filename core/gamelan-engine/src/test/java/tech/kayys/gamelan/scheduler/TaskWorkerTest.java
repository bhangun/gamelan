package tech.kayys.gamelan.scheduler;

import static tech.kayys.gamelan.engine.executor.ExecutorSelectionRejectionReasons.CAPACITY_SATURATED;
import static tech.kayys.gamelan.engine.executor.ExecutorSelectionRejectionReasons.INVALID_CAPACITY_METADATA;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.dispatcher.TaskDispatcherAggregator;
import tech.kayys.gamelan.engine.executor.ExecutorInfo;
import tech.kayys.gamelan.engine.node.NodeExecutionTask;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.protocol.CommunicationType;
import tech.kayys.gamelan.engine.run.RetryPolicy;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;
import tech.kayys.gamelan.registry.ExecutorRegistry;
import tech.kayys.gamelan.registry.ExecutorSelectionRequest;
import tech.kayys.gamelan.registry.ExecutorSelectionReport;

class TaskWorkerTest {

    private static final WorkflowRunId RUN_ID = WorkflowRunId.of("run-1");
    private static final NodeId NODE_ID = NodeId.of("node-1");

    @Test
    void processTaskDispatchesAndAcknowledgesWhenExecutorIsAvailable() {
        RecordingTaskQueue queue = new RecordingTaskQueue();
        RecordingExecutorRegistry registry = new RecordingExecutorRegistry(Optional.of(executor()), Map.of());
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        RecordingDeadLetterQueue deadLetters = new RecordingDeadLetterQueue();
        TaskWorker worker = worker(queue, registry, dispatcher, deadLetters);

        NodeExecutionTask task = task();
        worker.processTask(new TaskQueue.QueuedTask("message-1", task)).await().indefinitely();

        assertEquals(1, dispatcher.dispatchCount);
        assertSame(task, dispatcher.dispatchedTask);
        assertEquals("executor-1", dispatcher.dispatchedExecutor.executorId());
        assertEquals(1, queue.acknowledgeCount);
        assertEquals("message-1", queue.acknowledgedMessageId);
        assertEquals("message-1", queue.acknowledgedLeaseId);
        assertEquals(0, queue.deferCount);
        assertEquals(0, deadLetters.publishCount);
        assertEquals("agent", registry.lastRequest.executorType());
    }

    @Test
    void processTaskDefersWhenExecutorCapacityIsUnavailable() {
        RecordingTaskQueue queue = new RecordingTaskQueue();
        RecordingExecutorRegistry registry = new RecordingExecutorRegistry(
                Optional.empty(),
                Map.of(CAPACITY_SATURATED, 2));
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        RecordingDeadLetterQueue deadLetters = new RecordingDeadLetterQueue();
        TaskWorker worker = worker(queue, registry, dispatcher, deadLetters);

        NodeExecutionTask task = task();
        worker.processTask(new TaskQueue.QueuedTask("message-1", task)).await().indefinitely();

        assertEquals(0, dispatcher.dispatchCount);
        assertEquals(1, queue.deferCount);
        assertSame(task, queue.deferredTask.task());
        assertEquals(Duration.ZERO, queue.deferDelay);
        assertEquals(CAPACITY_SATURATED, queue.deferReason);
        assertEquals(0, queue.acknowledgeCount);
        assertEquals(0, deadLetters.publishCount);
    }

    @Test
    void processTaskDeadLettersBeforeExecutorLookupWhenDeliveryAttemptBudgetIsExceeded() {
        RecordingTaskQueue queue = new RecordingTaskQueue();
        RecordingExecutorRegistry registry = new RecordingExecutorRegistry(Optional.of(executor()), Map.of());
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        RecordingDeadLetterQueue deadLetters = new RecordingDeadLetterQueue();
        TaskWorker worker = worker(queue, registry, dispatcher, deadLetters);
        worker.maxDeliveryAttempts = 2;

        Instant leaseExpiresAt = Instant.parse("2026-05-30T01:00:00Z");
        NodeExecutionTask task = taskWithQueueMetadata(3, 1, CAPACITY_SATURATED);
        worker.processTask(new TaskQueue.QueuedTask("message-1", task, "lease-1", leaseExpiresAt))
                .await().indefinitely();

        assertNull(registry.lastRequest);
        assertEquals(0, dispatcher.dispatchCount);
        assertEquals(0, queue.deferCount);
        assertEquals(1, queue.acknowledgeCount);
        assertEquals("message-1", queue.acknowledgedMessageId);
        assertEquals("lease-1", queue.acknowledgedLeaseId);
        assertEquals(1, deadLetters.publishCount);
        assertSame(task, deadLetters.deadLetter.task());
        assertEquals(TaskDeadLetterReasons.MAX_DELIVERY_ATTEMPTS_EXCEEDED, deadLetters.deadLetter.reason());
        assertEquals(3, deadLetters.deadLetter.deliveryAttempt());
        assertEquals(1, deadLetters.deadLetter.deferCount());
        assertEquals(NoExecutorTaskDecision.ACTION_DEAD_LETTER,
                deadLetters.deadLetter.diagnostics().get(NoExecutorTaskDecision.DIAGNOSTIC_WORKER_DECISION));
        assertEquals(TaskDeadLetterReasons.MAX_DELIVERY_ATTEMPTS_EXCEEDED,
                deadLetters.deadLetter.diagnostics().get("deadLetterReason"));
        assertEquals(3,
                deadLetters.deadLetter.diagnostics().get(NoExecutorTaskDecision.DIAGNOSTIC_DELIVERY_ATTEMPT));
        assertEquals(1,
                deadLetters.deadLetter.diagnostics().get(NoExecutorTaskDecision.DIAGNOSTIC_DEFER_COUNT));
        assertEquals(2, deadLetters.deadLetter.diagnostics().get("maxDeliveryAttempts"));
        assertEquals(true, deadLetters.deadLetter.diagnostics().get("deliveryBudgetExhausted"));
        assertEquals("lease-1", deadLetters.deadLetter.diagnostics().get("leaseId"));
        assertEquals(leaseExpiresAt.toString(), deadLetters.deadLetter.diagnostics().get("leaseExpiresAt"));
    }

    @Test
    void processTaskAllowsDeliveryAttemptAtConfiguredBudget() {
        RecordingTaskQueue queue = new RecordingTaskQueue();
        RecordingExecutorRegistry registry = new RecordingExecutorRegistry(Optional.of(executor()), Map.of());
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        RecordingDeadLetterQueue deadLetters = new RecordingDeadLetterQueue();
        TaskWorker worker = worker(queue, registry, dispatcher, deadLetters);
        worker.maxDeliveryAttempts = 2;

        NodeExecutionTask task = taskWithQueueMetadata(2, 1, CAPACITY_SATURATED);
        worker.processTask(new TaskQueue.QueuedTask("message-1", task)).await().indefinitely();

        assertEquals(1, dispatcher.dispatchCount);
        assertEquals(1, queue.acknowledgeCount);
        assertEquals(0, queue.deferCount);
        assertEquals(0, deadLetters.publishCount);
        assertEquals("agent", registry.lastRequest.executorType());
    }

    @Test
    void processTaskRenewsLeaseWhileDispatchIsInFlight() {
        RecordingTaskQueue queue = new RecordingTaskQueue();
        RecordingExecutorRegistry registry = new RecordingExecutorRegistry(Optional.of(executor()), Map.of());
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        dispatcher.dispatchDelay = Duration.ofMillis(120);
        RecordingDeadLetterQueue deadLetters = new RecordingDeadLetterQueue();
        TaskWorker worker = worker(queue, registry, dispatcher, deadLetters);
        worker.leaseRenewalInterval = Duration.ofMillis(10);
        worker.leaseRenewalDuration = Duration.ofSeconds(1);

        Instant leaseExpiresAt = Instant.now().plusSeconds(30);
        NodeExecutionTask task = task();
        try {
            worker.processTask(new TaskQueue.QueuedTask("message-1", task, "lease-1", leaseExpiresAt))
                    .await().indefinitely();
        } finally {
            worker.stopLeaseRenewalScheduler();
        }

        assertTrue(queue.renewLeaseCount > 0);
        assertSame(task, queue.renewedTask.task());
        assertEquals("lease-1", queue.renewedTask.leaseId());
        assertEquals(Duration.ofSeconds(1), queue.renewedLeaseDuration);
        assertEquals(1, dispatcher.dispatchCount);
        assertEquals(1, queue.acknowledgeCount);
        assertEquals("lease-1", queue.acknowledgedLeaseId);
        assertEquals(0, deadLetters.publishCount);
    }

    @Test
    void processTasksHonorsConfiguredConcurrencyLimit() {
        RecordingTaskQueue queue = new RecordingTaskQueue();
        RecordingExecutorRegistry registry = new RecordingExecutorRegistry(Optional.of(executor()), Map.of());
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        dispatcher.dispatchDelay = Duration.ofMillis(80);
        RecordingDeadLetterQueue deadLetters = new RecordingDeadLetterQueue();
        TaskWorker worker = worker(queue, registry, dispatcher, deadLetters);
        worker.leaseRenewalEnabled = false;

        worker.processTasks(Multi.createFrom().items(
                        new TaskQueue.QueuedTask("message-1", task()),
                        new TaskQueue.QueuedTask("message-2", task(NodeId.of("node-2")))),
                1)
                .collect().asList()
                .await().indefinitely();

        assertEquals(2, dispatcher.dispatchCount);
        assertEquals(1, dispatcher.maxConcurrentDispatchCount);
        assertEquals(2, queue.acknowledgeCount);
        assertEquals(0, queue.deferCount);
        assertEquals(0, deadLetters.publishCount);
    }

    @Test
    void processTasksContinuesAfterOneTaskProcessingFailure() {
        RecordingTaskQueue queue = new RecordingTaskQueue();
        RecordingExecutorRegistry registry = new RecordingExecutorRegistry(Optional.of(executor()), Map.of());
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        dispatcher.failNodeId = NODE_ID;
        RecordingDeadLetterQueue deadLetters = new RecordingDeadLetterQueue();
        TaskWorker worker = worker(queue, registry, dispatcher, deadLetters);

        worker.processTasks(Multi.createFrom().items(
                        new TaskQueue.QueuedTask("message-1", task()),
                        new TaskQueue.QueuedTask("message-2", task(NodeId.of("node-2")))),
                2)
                .collect().asList()
                .await().indefinitely();

        assertEquals(2, dispatcher.dispatchCount);
        assertEquals(1, queue.acknowledgeCount);
        assertEquals("message-2", queue.acknowledgedMessageId);
        assertEquals(0, queue.deferCount);
        assertEquals(0, deadLetters.publishCount);
    }

    @Test
    void processTasksTimesOutHungDispatchAndContinues() {
        RecordingTaskQueue queue = new RecordingTaskQueue();
        RecordingExecutorRegistry registry = new RecordingExecutorRegistry(Optional.of(executor()), Map.of());
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        dispatcher.hangNodeId = NODE_ID;
        RecordingDeadLetterQueue deadLetters = new RecordingDeadLetterQueue();
        TaskWorker worker = worker(queue, registry, dispatcher, deadLetters);
        worker.leaseRenewalEnabled = false;
        worker.dispatchTimeout = Duration.ofMillis(20);

        worker.processTasks(Multi.createFrom().items(
                        new TaskQueue.QueuedTask("message-1", task()),
                        new TaskQueue.QueuedTask("message-2", task(NodeId.of("node-2")))),
                1)
                .collect().asList()
                .await().atMost(Duration.ofSeconds(2));

        assertEquals(2, dispatcher.dispatchCount);
        assertEquals(1, queue.acknowledgeCount);
        assertEquals("message-2", queue.acknowledgedMessageId);
        assertEquals(0, queue.deferCount);
        assertEquals(0, deadLetters.publishCount);
    }

    @Test
    void supervisedTaskStreamRetriesAfterQueueStreamFailure() {
        RecordingTaskQueue queue = new RecordingTaskQueue();
        RecordingExecutorRegistry registry = new RecordingExecutorRegistry(Optional.of(executor()), Map.of());
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        RecordingDeadLetterQueue deadLetters = new RecordingDeadLetterQueue();
        TaskWorker worker = worker(queue, registry, dispatcher, deadLetters);
        worker.consumeRetryInitialBackoff = Duration.ofMillis(1);
        worker.consumeRetryMaxBackoff = Duration.ofMillis(1);
        AtomicInteger subscriptions = new AtomicInteger();

        worker.supervisedTaskStream(() -> {
            if (subscriptions.incrementAndGet() == 1) {
                return Multi.createFrom().failure(new IllegalStateException("queue stream failed"));
            }
            return Multi.createFrom().item(new TaskQueue.QueuedTask("message-1", task()));
        }, 1)
                .collect().asList()
                .await().indefinitely();

        assertEquals(2, subscriptions.get());
        assertEquals(1, dispatcher.dispatchCount);
        assertEquals(1, queue.acknowledgeCount);
        assertEquals("message-1", queue.acknowledgedMessageId);
        assertEquals(0, deadLetters.publishCount);
        assertEquals(1, worker.status().queueStreamFailures());
        assertNotNull(worker.status().lastQueueStreamFailure());
    }

    @Test
    void stopCancelsWorkerSubscription() {
        RecordingTaskQueue queue = new RecordingTaskQueue();
        RecordingExecutorRegistry registry = new RecordingExecutorRegistry(Optional.of(executor()), Map.of());
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        RecordingDeadLetterQueue deadLetters = new RecordingDeadLetterQueue();
        TaskWorker worker = worker(queue, registry, dispatcher, deadLetters);
        AtomicBoolean terminated = new AtomicBoolean();

        worker.subscribeWorker(
                () -> Multi.createFrom().<TaskQueue.QueuedTask>emitter(
                        emitter -> emitter.onTermination(() -> terminated.set(true))),
                1);
        worker.stopWorkerSubscription();

        assertTrue(terminated.get());
    }

    @Test
    void pauseCancelsConsumptionAndReportsPaused() {
        StreamingTaskQueue queue = new StreamingTaskQueue();
        RecordingExecutorRegistry registry = new RecordingExecutorRegistry(Optional.of(executor()), Map.of());
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        RecordingDeadLetterQueue deadLetters = new RecordingDeadLetterQueue();
        TaskWorker worker = worker(queue, registry, dispatcher, deadLetters);

        worker.subscribeWorker(queue::consume, 1);
        TaskWorker.WorkerControlResult result = worker.pause();

        assertEquals(TaskWorker.WorkerControlAction.PAUSE, result.action());
        assertTrue(result.accepted());
        assertTrue(result.completed());
        assertEquals("worker-paused", result.reason());
        assertEquals(TaskWorker.WorkerState.PAUSED, result.status().state());
        assertTrue(result.status().operatorPaused());
        assertTrue(result.status().stopping());
        assertFalse(result.status().subscriptionActive());
        assertTrue(queue.terminated.get());
    }

    @Test
    void drainWaitsForInFlightTasksAndReportsDrained() throws InterruptedException {
        RecordingTaskQueue queue = new RecordingTaskQueue();
        RecordingExecutorRegistry registry = new RecordingExecutorRegistry(Optional.of(executor()), Map.of());
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        dispatcher.dispatchDelay = Duration.ofMillis(60);
        dispatcher.dispatchStartedLatch = new CountDownLatch(1);
        RecordingDeadLetterQueue deadLetters = new RecordingDeadLetterQueue();
        TaskWorker worker = worker(queue, registry, dispatcher, deadLetters);
        worker.shutdownGracePeriod = Duration.ofSeconds(1);

        worker.subscribeWorker(
                () -> Multi.createFrom().item(new TaskQueue.QueuedTask("message-1", task())),
                1);

        assertTrue(dispatcher.dispatchStartedLatch.await(1, TimeUnit.SECONDS));

        TaskWorker.WorkerControlResult result = assertTimeoutPreemptively(
                Duration.ofSeconds(2),
                worker::drain);

        assertEquals(TaskWorker.WorkerControlAction.DRAIN, result.action());
        assertTrue(result.accepted());
        assertTrue(result.completed());
        assertEquals("worker-drained", result.reason());
        assertEquals(TaskWorker.WorkerState.PAUSED, result.status().state());
        assertEquals(0, result.status().inFlightCount());
        assertEquals(1, queue.acknowledgeCount);
    }

    @Test
    void resumeRejectsWhileWorkerIsStillDraining() throws InterruptedException {
        RecordingTaskQueue queue = new RecordingTaskQueue();
        RecordingExecutorRegistry registry = new RecordingExecutorRegistry(Optional.of(executor()), Map.of());
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        dispatcher.hangNodeId = NODE_ID;
        dispatcher.dispatchStartedLatch = new CountDownLatch(1);
        RecordingDeadLetterQueue deadLetters = new RecordingDeadLetterQueue();
        TaskWorker worker = worker(queue, registry, dispatcher, deadLetters);
        worker.dispatchTimeout = Duration.ofMillis(50);
        worker.shutdownGracePeriod = Duration.ZERO;

        worker.subscribeWorker(
                () -> Multi.createFrom().item(new TaskQueue.QueuedTask("message-1", task())),
                1);

        assertTrue(dispatcher.dispatchStartedLatch.await(1, TimeUnit.SECONDS));
        TaskWorker.WorkerControlResult pause = worker.pause();
        TaskWorker.WorkerControlResult resume = worker.resume();

        assertEquals(TaskWorker.WorkerState.DRAINING, pause.status().state());
        assertEquals(TaskWorker.WorkerControlAction.RESUME, resume.action());
        assertFalse(resume.accepted());
        assertFalse(resume.completed());
        assertEquals("in-flight-tasks-active", resume.reason());
        assertEquals(TaskWorker.WorkerState.DRAINING, resume.status().state());

        worker.stop();
    }

    @Test
    void resumeStartsConsumptionAfterPause() {
        StreamingTaskQueue queue = new StreamingTaskQueue();
        RecordingExecutorRegistry registry = new RecordingExecutorRegistry(Optional.of(executor()), Map.of());
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        RecordingDeadLetterQueue deadLetters = new RecordingDeadLetterQueue();
        TaskWorker worker = worker(queue, registry, dispatcher, deadLetters);

        worker.pause();
        TaskWorker.WorkerControlResult result = worker.resume();

        assertEquals(TaskWorker.WorkerControlAction.RESUME, result.action());
        assertTrue(result.accepted());
        assertTrue(result.completed());
        assertEquals("worker-resumed", result.reason());
        assertEquals(TaskWorker.WorkerState.RUNNING, result.status().state());
        assertTrue(result.status().subscriptionActive());

        worker.stop();
    }

    @Test
    void stopWaitsForInFlightTasksWithinGracePeriod() throws InterruptedException {
        RecordingTaskQueue queue = new RecordingTaskQueue();
        RecordingExecutorRegistry registry = new RecordingExecutorRegistry(Optional.of(executor()), Map.of());
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        dispatcher.dispatchDelay = Duration.ofMillis(80);
        dispatcher.dispatchStartedLatch = new CountDownLatch(1);
        RecordingDeadLetterQueue deadLetters = new RecordingDeadLetterQueue();
        TaskWorker worker = worker(queue, registry, dispatcher, deadLetters);
        worker.shutdownGracePeriod = Duration.ofSeconds(1);

        worker.subscribeWorker(
                () -> Multi.createFrom().item(new TaskQueue.QueuedTask("message-1", task())),
                1);

        assertTrue(dispatcher.dispatchStartedLatch.await(1, TimeUnit.SECONDS));

        assertTimeoutPreemptively(Duration.ofSeconds(2), worker::stop);

        assertEquals(1, dispatcher.dispatchCount);
        assertEquals(1, queue.acknowledgeCount);
        assertEquals("message-1", queue.acknowledgedMessageId);
        assertEquals(0, worker.inFlightTaskCount());
    }

    @Test
    void stopReturnsAfterGracePeriodWhenInFlightTaskDoesNotFinish() throws InterruptedException {
        RecordingTaskQueue queue = new RecordingTaskQueue();
        RecordingExecutorRegistry registry = new RecordingExecutorRegistry(Optional.of(executor()), Map.of());
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        dispatcher.hangNodeId = NODE_ID;
        dispatcher.dispatchStartedLatch = new CountDownLatch(1);
        RecordingDeadLetterQueue deadLetters = new RecordingDeadLetterQueue();
        TaskWorker worker = worker(queue, registry, dispatcher, deadLetters);
        worker.dispatchTimeout = Duration.ofMinutes(5);
        worker.shutdownGracePeriod = Duration.ofMillis(20);

        worker.subscribeWorker(
                () -> Multi.createFrom().item(new TaskQueue.QueuedTask("message-1", task())),
                1);

        assertTrue(dispatcher.dispatchStartedLatch.await(1, TimeUnit.SECONDS));

        assertTimeoutPreemptively(Duration.ofSeconds(1), worker::stop);

        assertEquals(1, dispatcher.dispatchCount);
        assertEquals(0, queue.acknowledgeCount);
        assertEquals(1, worker.inFlightTaskCount());
    }

    @Test
    void statusReportsStoppedWorkerConfiguration() {
        RecordingTaskQueue queue = new RecordingTaskQueue();
        RecordingExecutorRegistry registry = new RecordingExecutorRegistry(Optional.of(executor()), Map.of());
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        RecordingDeadLetterQueue deadLetters = new RecordingDeadLetterQueue();
        TaskWorker worker = worker(queue, registry, dispatcher, deadLetters);

        TaskWorker.WorkerStatus status = worker.status();

        assertEquals(TaskWorker.WorkerState.STOPPED, status.state());
        assertFalse(status.running());
        assertFalse(status.stopping());
        assertFalse(status.operatorPaused());
        assertFalse(status.subscriptionActive());
        assertEquals("RecordingTaskQueue", status.queueImplementation());
        assertEquals("RecordingDispatcher", status.dispatcherImplementation());
        assertEquals(64, status.maxConcurrentTasks());
        assertEquals(64, status.remainingCapacity());
        assertEquals(0, status.inFlightCount());
        assertEquals(100, status.maxDeliveryAttempts());
        assertEquals("PT5M", status.dispatchTimeout());
        assertEquals("PT30S", status.shutdownGracePeriod());
        assertEquals(0, status.acceptedTasks());
        assertEquals(0, status.finishedTasks());
        assertTrue(status.inFlightTasks().isEmpty());
        assertNotNull(status.observedAt());
    }

    @Test
    void statusReportsActiveSubscriptionAndInFlightTask() throws InterruptedException {
        RecordingTaskQueue queue = new RecordingTaskQueue();
        RecordingExecutorRegistry registry = new RecordingExecutorRegistry(Optional.of(executor()), Map.of());
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        dispatcher.dispatchDelay = Duration.ofMillis(120);
        dispatcher.dispatchStartedLatch = new CountDownLatch(1);
        RecordingDeadLetterQueue deadLetters = new RecordingDeadLetterQueue();
        TaskWorker worker = worker(queue, registry, dispatcher, deadLetters);
        worker.leaseRenewalEnabled = false;

        worker.subscribeWorker(
                () -> Multi.createFrom().item(new TaskQueue.QueuedTask("message-1", task())),
                1);

        assertTrue(dispatcher.dispatchStartedLatch.await(1, TimeUnit.SECONDS));

        TaskWorker.WorkerStatus activeStatus = worker.status();

        assertEquals(TaskWorker.WorkerState.RUNNING, activeStatus.state());
        assertTrue(activeStatus.running());
        assertTrue(activeStatus.subscriptionActive());
        assertEquals(1, activeStatus.inFlightCount());
        assertEquals(63, activeStatus.remainingCapacity());
        assertEquals(1, activeStatus.acceptedTasks());
        assertEquals(0, activeStatus.finishedTasks());
        assertEquals("message-1", activeStatus.inFlightTasks().getFirst().messageId());
        assertEquals("run-1", activeStatus.inFlightTasks().getFirst().runId());
        assertEquals("node-1", activeStatus.inFlightTasks().getFirst().nodeId());

        worker.stop();

        TaskWorker.WorkerStatus stoppedStatus = worker.status();
        assertEquals(TaskWorker.WorkerState.STOPPED, stoppedStatus.state());
        assertEquals(0, stoppedStatus.inFlightCount());
        assertEquals(1, stoppedStatus.finishedTasks());
    }

    @Test
    void defaultDeferRequeuesTaskWithDeliveryMetadataThenAcknowledgesOriginalMessage() {
        DefaultDeferTaskQueue queue = new DefaultDeferTaskQueue();
        NodeExecutionTask task = task();

        queue.defer(new TaskQueue.QueuedTask("message-1", task), Duration.ZERO, CAPACITY_SATURATED)
                .await().indefinitely();

        assertEquals(1, queue.enqueueCount);
        assertEquals(2, queue.enqueuedTask.context().get(TaskQueueMetadata.DELIVERY_ATTEMPT_KEY));
        assertEquals(1, queue.enqueuedTask.context().get(TaskQueueMetadata.DEFER_COUNT_KEY));
        assertEquals(CAPACITY_SATURATED, queue.enqueuedTask.context().get(TaskQueueMetadata.LAST_DEFER_REASON_KEY));
        assertTrue(queue.enqueuedTask.context().containsKey(TaskQueueMetadata.FIRST_SEEN_AT_KEY));
        assertEquals(1, queue.acknowledgeCount);
        assertEquals("message-1", queue.acknowledgedMessageId);
        assertEquals("message-1", queue.acknowledgedLeaseId);
    }

    @Test
    void processTaskDeadLettersPermanentNoExecutorReason() {
        RecordingTaskQueue queue = new RecordingTaskQueue();
        RecordingExecutorRegistry registry = new RecordingExecutorRegistry(
                Optional.empty(),
                Map.of(INVALID_CAPACITY_METADATA, 1));
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        RecordingDeadLetterQueue deadLetters = new RecordingDeadLetterQueue();
        TaskWorker worker = worker(queue, registry, dispatcher, deadLetters);

        NodeExecutionTask task = task();
        worker.processTask(new TaskQueue.QueuedTask("message-1", task)).await().indefinitely();

        assertEquals(0, dispatcher.dispatchCount);
        assertEquals(0, queue.deferCount);
        assertEquals(1, queue.acknowledgeCount);
        assertEquals("message-1", queue.acknowledgedLeaseId);
        assertEquals(1, deadLetters.publishCount);
        assertSame(task, deadLetters.deadLetter.task());
        assertEquals(INVALID_CAPACITY_METADATA, deadLetters.deadLetter.reason());
        assertEquals(0, deadLetters.deadLetter.deferCount());
        assertEquals(NoExecutorTaskDecision.ACTION_DEAD_LETTER,
                deadLetters.deadLetter.diagnostics().get(NoExecutorTaskDecision.DIAGNOSTIC_WORKER_DECISION));
        assertEquals(INVALID_CAPACITY_METADATA,
                deadLetters.deadLetter.diagnostics().get(NoExecutorTaskDecision.DIAGNOSTIC_SELECTION_REASON));
        assertEquals(true,
                deadLetters.deadLetter.diagnostics()
                        .get(NoExecutorTaskDecision.DIAGNOSTIC_PERMANENT_SELECTION_FAILURE));
        assertEquals(false,
                deadLetters.deadLetter.diagnostics().get(NoExecutorTaskDecision.DIAGNOSTIC_DEFER_BUDGET_EXHAUSTED));
    }

    @Test
    void processTaskDeadLettersAfterNoExecutorDeferBudgetIsExhausted() {
        RecordingTaskQueue queue = new RecordingTaskQueue();
        RecordingExecutorRegistry registry = new RecordingExecutorRegistry(
                Optional.empty(),
                Map.of(CAPACITY_SATURATED, 2));
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        RecordingDeadLetterQueue deadLetters = new RecordingDeadLetterQueue();
        TaskWorker worker = worker(queue, registry, dispatcher, deadLetters);
        worker.maxNoExecutorDefers = 2;

        NodeExecutionTask task = taskWithQueueMetadata(3, 2, CAPACITY_SATURATED);
        worker.processTask(new TaskQueue.QueuedTask("message-1", task)).await().indefinitely();

        assertEquals(0, dispatcher.dispatchCount);
        assertEquals(0, queue.deferCount);
        assertEquals(1, queue.acknowledgeCount);
        assertEquals("message-1", queue.acknowledgedLeaseId);
        assertEquals(1, deadLetters.publishCount);
        assertEquals(CAPACITY_SATURATED, deadLetters.deadLetter.reason());
        assertEquals(3, deadLetters.deadLetter.deliveryAttempt());
        assertEquals(2, deadLetters.deadLetter.deferCount());
        assertEquals(NoExecutorTaskDecision.ACTION_DEAD_LETTER,
                deadLetters.deadLetter.diagnostics().get(NoExecutorTaskDecision.DIAGNOSTIC_WORKER_DECISION));
        assertEquals(CAPACITY_SATURATED,
                deadLetters.deadLetter.diagnostics().get(NoExecutorTaskDecision.DIAGNOSTIC_SELECTION_REASON));
        assertEquals(false,
                deadLetters.deadLetter.diagnostics()
                        .get(NoExecutorTaskDecision.DIAGNOSTIC_PERMANENT_SELECTION_FAILURE));
        assertEquals(true,
                deadLetters.deadLetter.diagnostics().get(NoExecutorTaskDecision.DIAGNOSTIC_DEFER_BUDGET_EXHAUSTED));
        assertEquals(2, deadLetters.deadLetter.diagnostics().get(NoExecutorTaskDecision.DIAGNOSTIC_MAX_DEFERS));
    }

    @Test
    void inMemoryDeadLetterQueueListsRecentEntriesAndClears() {
        InMemoryTaskDeadLetterQueue queue = new InMemoryTaskDeadLetterQueue();
        TaskDeadLetterQueue.DeadLetterTask first = deadLetter("message-1", CAPACITY_SATURATED);
        TaskDeadLetterQueue.DeadLetterTask second = deadLetter("message-2", INVALID_CAPACITY_METADATA);

        queue.publish(first).await().indefinitely();
        queue.publish(second).await().indefinitely();

        assertEquals(2L, queue.count().await().indefinitely());
        assertEquals("message-2", queue.list(1).await().indefinitely().getFirst().messageId());

        queue.clear().await().indefinitely();

        assertEquals(0L, queue.count().await().indefinitely());
        assertTrue(queue.list(10).await().indefinitely().isEmpty());
    }

    @Test
    void inMemoryDeadLetterQueueFiltersByRunNodeTenantAndReason() {
        InMemoryTaskDeadLetterQueue queue = new InMemoryTaskDeadLetterQueue();
        queue.publish(deadLetter(
                "message-1",
                CAPACITY_SATURATED,
                RUN_ID,
                NODE_ID,
                "tenant-a")).await().indefinitely();
        queue.publish(deadLetter(
                "message-2",
                INVALID_CAPACITY_METADATA,
                WorkflowRunId.of("run-2"),
                NodeId.of("node-2"),
                "tenant-b")).await().indefinitely();
        queue.publish(deadLetter(
                "message-3",
                CAPACITY_SATURATED,
                WorkflowRunId.of("run-3"),
                NODE_ID,
                "tenant-a")).await().indefinitely();

        assertEquals(2L, queue.count(new TaskDeadLetterQueue.DeadLetterQuery(
                100,
                null,
                null,
                "tenant-a",
                CAPACITY_SATURATED)).await().indefinitely());
        assertEquals("message-3", queue.list(new TaskDeadLetterQueue.DeadLetterQuery(
                100,
                null,
                NODE_ID.value(),
                "tenant-a",
                CAPACITY_SATURATED)).await().indefinitely().getFirst().messageId());
        assertEquals("message-2", queue.list(new TaskDeadLetterQueue.DeadLetterQuery(
                100,
                "run-2",
                "node-2",
                "tenant-b",
                INVALID_CAPACITY_METADATA)).await().indefinitely().getFirst().messageId());
        assertTrue(queue.list(new TaskDeadLetterQueue.DeadLetterQuery(
                100,
                RUN_ID.value(),
                NODE_ID.value(),
                "tenant-b",
                null)).await().indefinitely().isEmpty());
    }

    @Test
    void inMemoryDeadLetterQueueGetsAndDeletesByMessageId() {
        InMemoryTaskDeadLetterQueue queue = new InMemoryTaskDeadLetterQueue();
        queue.publish(deadLetter("message-1", CAPACITY_SATURATED)).await().indefinitely();
        queue.publish(deadLetter("message-2", INVALID_CAPACITY_METADATA)).await().indefinitely();

        assertEquals("message-2", queue.get("message-2").await().indefinitely().orElseThrow().messageId());
        assertTrue(queue.delete("message-2").await().indefinitely());
        assertTrue(queue.get("message-2").await().indefinitely().isEmpty());
        assertEquals(1L, queue.count().await().indefinitely());
        assertFalse(queue.delete("missing").await().indefinitely());
    }

    @Test
    void inMemoryDeadLetterQueueClearsOnlyMatchingEntries() {
        InMemoryTaskDeadLetterQueue queue = new InMemoryTaskDeadLetterQueue();
        queue.publish(deadLetter("message-1", CAPACITY_SATURATED, RUN_ID, NODE_ID, "tenant-a"))
                .await().indefinitely();
        queue.publish(deadLetter("message-2", INVALID_CAPACITY_METADATA, RUN_ID, NODE_ID, "tenant-a"))
                .await().indefinitely();
        queue.publish(deadLetter(
                "message-3",
                CAPACITY_SATURATED,
                WorkflowRunId.of("run-2"),
                NODE_ID,
                "tenant-b")).await().indefinitely();

        long deleted = queue.clear(new TaskDeadLetterQueue.DeadLetterQuery(
                100,
                null,
                null,
                "tenant-a",
                CAPACITY_SATURATED)).await().indefinitely();

        assertEquals(1L, deleted);
        assertTrue(queue.get("message-1").await().indefinitely().isEmpty());
        assertEquals(2L, queue.count().await().indefinitely());
        assertEquals("message-3", queue.list(10).await().indefinitely().getFirst().messageId());
    }

    @Test
    void withoutQueueMetadataRemovesOnlyQueueDeliveryFields() {
        NodeExecutionTask task = taskWithQueueMetadata(3, 2, CAPACITY_SATURATED);

        NodeExecutionTask cleaned = TaskQueueMetadata.withoutQueueMetadata(task);

        assertEquals(task.runId(), cleaned.runId());
        assertEquals(task.nodeId(), cleaned.nodeId());
        assertEquals(task.attempt(), cleaned.attempt());
        assertEquals(task.token(), cleaned.token());
        assertSame(task.retryPolicy(), cleaned.retryPolicy());
        assertEquals("agent", cleaned.context().get(NodeExecutionTask.NODE_TYPE_KEY));
        assertFalse(cleaned.context().containsKey(TaskQueueMetadata.DELIVERY_ATTEMPT_KEY));
        assertFalse(cleaned.context().containsKey(TaskQueueMetadata.DEFER_COUNT_KEY));
        assertFalse(cleaned.context().containsKey(TaskQueueMetadata.FIRST_SEEN_AT_KEY));
        assertFalse(cleaned.context().containsKey(TaskQueueMetadata.LAST_DEFER_REASON_KEY));
    }

    private static TaskWorker worker(
            TaskQueue queue,
            RecordingExecutorRegistry registry,
            RecordingDispatcher dispatcher,
            RecordingDeadLetterQueue deadLetters) {
        TaskWorker worker = new TaskWorker();
        worker.taskQueue = queue;
        worker.executorRegistry = registry;
        worker.taskDispatcher = dispatcher;
        worker.deadLetterQueue = deadLetters;
        worker.noExecutorDeferDelay = Duration.ZERO;
        worker.maxNoExecutorDefers = 30;
        worker.maxConcurrentTasks = 64;
        worker.maxDeliveryAttempts = 100;
        worker.leaseRenewalEnabled = true;
        worker.leaseRenewalInterval = Duration.ofSeconds(10);
        worker.leaseRenewalDuration = Duration.ofSeconds(30);
        worker.consumeRetryInitialBackoff = Duration.ofSeconds(1);
        worker.consumeRetryMaxBackoff = Duration.ofSeconds(30);
        worker.dispatchTimeout = Duration.ofMinutes(5);
        worker.shutdownGracePeriod = Duration.ofSeconds(30);
        return worker;
    }

    private static NodeExecutionTask task() {
        return task(NODE_ID);
    }

    private static NodeExecutionTask task(NodeId nodeId) {
        return new NodeExecutionTask(
                RUN_ID,
                nodeId,
                1,
                null,
                Map.of(NodeExecutionTask.NODE_TYPE_KEY, "agent"),
                RetryPolicy.none());
    }

    private static NodeExecutionTask taskWithQueueMetadata(
            int deliveryAttempt,
            int deferCount,
            String lastDeferReason) {
        Map<String, Object> context = new HashMap<>();
        context.put(NodeExecutionTask.NODE_TYPE_KEY, "agent");
        context.put(TaskQueueMetadata.DELIVERY_ATTEMPT_KEY, deliveryAttempt);
        context.put(TaskQueueMetadata.DEFER_COUNT_KEY, deferCount);
        context.put(TaskQueueMetadata.FIRST_SEEN_AT_KEY, Instant.now().minusSeconds(30).toString());
        context.put(TaskQueueMetadata.LAST_DEFER_REASON_KEY, lastDeferReason);
        return new NodeExecutionTask(
                RUN_ID,
                NODE_ID,
                1,
                null,
                context,
                RetryPolicy.none());
    }

    private static ExecutorInfo executor() {
        return new ExecutorInfo(
                "executor-1",
                "agent",
                CommunicationType.LOCAL,
                "local",
                Duration.ofSeconds(30),
                Map.of());
    }

    private static TaskDeadLetterQueue.DeadLetterTask deadLetter(String messageId, String reason) {
        return deadLetter(messageId, reason, RUN_ID, NODE_ID, null);
    }

    private static TaskDeadLetterQueue.DeadLetterTask deadLetter(
            String messageId,
            String reason,
            WorkflowRunId runId,
            NodeId nodeId,
            String tenantId) {
        return new TaskDeadLetterQueue.DeadLetterTask(
                messageId,
                task(runId, nodeId, tenantId),
                reason,
                1,
                0,
                Instant.now(),
                Instant.now(),
                Map.of());
    }

    private static NodeExecutionTask task(
            WorkflowRunId runId,
            NodeId nodeId,
            String tenantId) {
        Map<String, Object> context = new HashMap<>();
        context.put(NodeExecutionTask.NODE_TYPE_KEY, "agent");
        if (tenantId != null) {
            context.put(NodeExecutionTask.TENANT_ID_KEY, tenantId);
        }
        return new NodeExecutionTask(
                runId,
                nodeId,
                1,
                null,
                context,
                RetryPolicy.none());
    }

    private static class RecordingTaskQueue implements TaskQueue {
        private int acknowledgeCount;
        private String acknowledgedMessageId;
        private String acknowledgedLeaseId;
        private int deferCount;
        private QueuedTask deferredTask;
        private Duration deferDelay;
        private String deferReason;
        private volatile int renewLeaseCount;
        private volatile QueuedTask renewedTask;
        private volatile Duration renewedLeaseDuration;
        private volatile boolean renewLeaseResult = true;

        @Override
        public Uni<Void> enqueue(NodeExecutionTask task) {
            return Uni.createFrom().voidItem();
        }

        @Override
        public Multi<QueuedTask> consume() {
            return Multi.createFrom().empty();
        }

        @Override
        public Uni<Void> acknowledge(String messageId) {
            acknowledgeCount++;
            acknowledgedMessageId = messageId;
            return Uni.createFrom().voidItem();
        }

        @Override
        public Uni<Void> acknowledge(QueuedTask queuedTask) {
            acknowledgeCount++;
            acknowledgedMessageId = queuedTask.messageId();
            acknowledgedLeaseId = queuedTask.leaseId();
            return Uni.createFrom().voidItem();
        }

        @Override
        public Uni<Void> defer(QueuedTask queuedTask, Duration delay, String reason) {
            deferCount++;
            deferredTask = queuedTask;
            deferDelay = delay;
            deferReason = reason;
            return Uni.createFrom().voidItem();
        }

        @Override
        public Uni<Boolean> renewLease(QueuedTask queuedTask, Duration leaseDuration) {
            renewLeaseCount++;
            renewedTask = queuedTask;
            renewedLeaseDuration = leaseDuration;
            return Uni.createFrom().item(renewLeaseResult);
        }
    }

    private static final class StreamingTaskQueue extends RecordingTaskQueue {
        private final AtomicBoolean terminated = new AtomicBoolean();

        @Override
        public Multi<QueuedTask> consume() {
            return Multi.createFrom().emitter(emitter -> emitter.onTermination(() -> terminated.set(true)));
        }
    }

    private static final class DefaultDeferTaskQueue implements TaskQueue {
        private int enqueueCount;
        private NodeExecutionTask enqueuedTask;
        private int acknowledgeCount;
        private String acknowledgedMessageId;
        private String acknowledgedLeaseId;

        @Override
        public Uni<Void> enqueue(NodeExecutionTask task) {
            enqueueCount++;
            enqueuedTask = task;
            return Uni.createFrom().voidItem();
        }

        @Override
        public Multi<QueuedTask> consume() {
            return Multi.createFrom().empty();
        }

        @Override
        public Uni<Void> acknowledge(String messageId) {
            acknowledgeCount++;
            acknowledgedMessageId = messageId;
            return Uni.createFrom().voidItem();
        }

        @Override
        public Uni<Void> acknowledge(QueuedTask queuedTask) {
            acknowledgeCount++;
            acknowledgedMessageId = queuedTask.messageId();
            acknowledgedLeaseId = queuedTask.leaseId();
            return Uni.createFrom().voidItem();
        }
    }

    private static final class RecordingExecutorRegistry extends ExecutorRegistry {
        private final Optional<ExecutorInfo> selectedExecutor;
        private final Map<String, Integer> rejectionCounts;
        private ExecutorSelectionRequest lastRequest;

        private RecordingExecutorRegistry(
                Optional<ExecutorInfo> selectedExecutor,
                Map<String, Integer> rejectionCounts) {
            this.selectedExecutor = selectedExecutor;
            this.rejectionCounts = rejectionCounts;
        }

        @Override
        public Uni<ExecutorSelectionReport> selectExecutorWithDiagnostics(ExecutorSelectionRequest request) {
            lastRequest = request;
            int selectedCount = selectedExecutor.isPresent() ? 1 : 0;
            return Uni.createFrom().item(new ExecutorSelectionReport(
                    request,
                    selectedExecutor,
                    selectedCount,
                    0,
                    selectedCount,
                    selectedCount,
                    selectedCount,
                    selectedCount,
                    selectedCount,
                    rejectionCounts,
                    Map.of("registry", "task-worker-test")));
        }
    }

    private static final class RecordingDispatcher extends TaskDispatcherAggregator {
        private int dispatchCount;
        private NodeExecutionTask dispatchedTask;
        private ExecutorInfo dispatchedExecutor;
        private Duration dispatchDelay = Duration.ZERO;
        private int currentDispatchCount;
        private int maxConcurrentDispatchCount;
        private NodeId failNodeId;
        private NodeId hangNodeId;
        private CountDownLatch dispatchStartedLatch;

        @Override
        public Uni<Void> dispatch(NodeExecutionTask task, ExecutorInfo executor) {
            beginDispatch(task, executor);
            Uni<Void> dispatch = dispatchResult(task);
            if (dispatchDelay != null && !dispatchDelay.isZero() && !dispatchDelay.isNegative()) {
                dispatch = dispatch.onItem().delayIt().by(dispatchDelay);
            }
            return dispatch.onTermination().invoke(this::endDispatch);
        }

        private Uni<Void> dispatchResult(NodeExecutionTask task) {
            if (shouldHang(task)) {
                return Uni.createFrom().nothing();
            }
            if (shouldFail(task)) {
                return Uni.createFrom().failure(new IllegalStateException("dispatch failed"));
            }
            return Uni.createFrom().voidItem();
        }

        private boolean shouldFail(NodeExecutionTask task) {
            return failNodeId != null && task != null && failNodeId.equals(task.nodeId());
        }

        private boolean shouldHang(NodeExecutionTask task) {
            return hangNodeId != null && task != null && hangNodeId.equals(task.nodeId());
        }

        private synchronized void beginDispatch(NodeExecutionTask task, ExecutorInfo executor) {
            dispatchCount++;
            dispatchedTask = task;
            dispatchedExecutor = executor;
            currentDispatchCount++;
            maxConcurrentDispatchCount = Math.max(maxConcurrentDispatchCount, currentDispatchCount);
            if (dispatchStartedLatch != null) {
                dispatchStartedLatch.countDown();
            }
        }

        private synchronized void endDispatch() {
            currentDispatchCount = Math.max(0, currentDispatchCount - 1);
        }
    }

    private static final class RecordingDeadLetterQueue implements TaskDeadLetterQueue {
        private int publishCount;
        private DeadLetterTask deadLetter;

        @Override
        public Uni<Void> publish(DeadLetterTask task) {
            publishCount++;
            deadLetter = task;
            return Uni.createFrom().voidItem();
        }
    }
}
