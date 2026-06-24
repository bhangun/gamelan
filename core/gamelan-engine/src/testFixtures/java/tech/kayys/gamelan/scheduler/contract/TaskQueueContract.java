package tech.kayys.gamelan.scheduler.contract;

import static tech.kayys.gamelan.engine.executor.ExecutorSelectionRejectionReasons.CAPACITY_SATURATED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import tech.kayys.gamelan.engine.node.NodeExecutionTask;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.run.RetryPolicy;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;
import tech.kayys.gamelan.scheduler.TaskQueue;
import tech.kayys.gamelan.scheduler.TaskQueueMetadata;

/**
 * Shared conformance tests for queue delivery metadata and basic enqueue/consume behavior.
 */
public interface TaskQueueContract {

    WorkflowRunId RUN_ID = WorkflowRunId.of("contract-queue-run");
    NodeId NODE_ID = NodeId.of("contract-queue-node");
    Instant FIRST_SEEN = Instant.parse("2026-05-27T00:00:00Z");

    TaskQueue newTaskQueue();

    @Test
    default void taskQueueContract_enqueueEmitsQueuedTaskAndAcknowledgesSafely() throws Exception {
        TaskQueue queue = newTaskQueue();
        CompletableFuture<TaskQueue.QueuedTask> received = firstQueuedTask(queue);
        NodeExecutionTask task = task(Map.of(NodeExecutionTask.NODE_TYPE_KEY, "agent"));

        queue.enqueue(task).await().indefinitely();
        TaskQueue.QueuedTask queuedTask = received.get(2, TimeUnit.SECONDS);
        queue.acknowledge(queuedTask).await().indefinitely();

        assertNotNull(queuedTask.messageId());
        assertFalse(queuedTask.messageId().isBlank());
        assertEquals(task.runId(), queuedTask.task().runId());
        assertEquals(task.nodeId(), queuedTask.task().nodeId());
        assertEquals(task.attempt(), queuedTask.task().attempt());
        assertSame(task.token(), queuedTask.task().token());
        assertEquals(task.retryPolicy(), queuedTask.task().retryPolicy());
        assertEquals("agent", queuedTask.task().context().get(NodeExecutionTask.NODE_TYPE_KEY));
        assertNotNull(queuedTask.leaseId());
        assertFalse(queuedTask.leaseId().isBlank());
        assertTrue(queuedTask.leaseActive(Instant.now()));
        assertEquals(1, queuedTask.deliveryAttempt());
        assertEquals(0, queuedTask.deferCount());
        assertTrue(queuedTask.lastDeferReason().isEmpty());
    }

    @Test
    default void taskQueueContract_deferRequeuesWithDeliveryMetadata() throws Exception {
        TaskQueue queue = newTaskQueue();
        CompletableFuture<TaskQueue.QueuedTask> received = firstQueuedTask(queue);
        NodeExecutionTask task = taskWithQueueMetadata(3, 2, FIRST_SEEN, "previous-reason");

        queue.defer(new TaskQueue.QueuedTask("contract-original-message", task), Duration.ZERO,
                " " + CAPACITY_SATURATED + " ")
                .await()
                .indefinitely();
        TaskQueue.QueuedTask deferred = received.get(2, TimeUnit.SECONDS);

        assertEquals(4, deferred.deliveryAttempt());
        assertEquals(3, deferred.deferCount());
        assertEquals(FIRST_SEEN, deferred.firstSeenAt());
        assertEquals(CAPACITY_SATURATED, deferred.lastDeferReason());
        assertEquals("agent", deferred.task().context().get(NodeExecutionTask.NODE_TYPE_KEY));
    }

    @Test
    default void taskQueueContract_queuedTaskNormalizesDeliveryMetadata() {
        NodeExecutionTask task = taskWithQueueMetadata("5", "-1", FIRST_SEEN.toString(), " needs-capacity ");
        TaskQueue.QueuedTask queuedTask = new TaskQueue.QueuedTask("contract-message", task);

        assertEquals(5, queuedTask.deliveryAttempt());
        assertEquals(0, queuedTask.deferCount());
        assertEquals(FIRST_SEEN, queuedTask.firstSeenAt());
        assertEquals("needs-capacity", queuedTask.lastDeferReason());
    }

    @Test
    default void taskQueueContract_queuedTaskCarriesLeaseMetadata() {
        NodeExecutionTask task = task(Map.of(NodeExecutionTask.NODE_TYPE_KEY, "agent"));
        TaskQueue.QueuedTask queuedTask = new TaskQueue.QueuedTask(
                " contract-message ",
                task,
                " lease-1 ",
                FIRST_SEEN.plusSeconds(60));

        assertEquals("contract-message", queuedTask.messageId());
        assertEquals("lease-1", queuedTask.leaseId());
        assertEquals(FIRST_SEEN.plusSeconds(60), queuedTask.leaseExpiresAt());
        assertFalse(queuedTask.leaseExpired(FIRST_SEEN));
        assertTrue(queuedTask.leaseExpired(FIRST_SEEN.plusSeconds(61)));
    }

    @Test
    default void taskQueueContract_withoutQueueMetadataPreservesDomainContextOnly() {
        NodeExecutionTask task = taskWithQueueMetadata(3, 2, FIRST_SEEN, CAPACITY_SATURATED);

        NodeExecutionTask cleaned = TaskQueueMetadata.withoutQueueMetadata(task);

        assertEquals(task.runId(), cleaned.runId());
        assertEquals(task.nodeId(), cleaned.nodeId());
        assertEquals(task.attempt(), cleaned.attempt());
        assertSame(task.token(), cleaned.token());
        assertEquals(task.retryPolicy(), cleaned.retryPolicy());
        assertEquals("agent", cleaned.context().get(NodeExecutionTask.NODE_TYPE_KEY));
        assertFalse(cleaned.context().containsKey(TaskQueueMetadata.DELIVERY_ATTEMPT_KEY));
        assertFalse(cleaned.context().containsKey(TaskQueueMetadata.DEFER_COUNT_KEY));
        assertFalse(cleaned.context().containsKey(TaskQueueMetadata.FIRST_SEEN_AT_KEY));
        assertFalse(cleaned.context().containsKey(TaskQueueMetadata.LAST_DEFER_REASON_KEY));
    }

    private static CompletableFuture<TaskQueue.QueuedTask> firstQueuedTask(TaskQueue queue) {
        CompletableFuture<TaskQueue.QueuedTask> received = new CompletableFuture<>();
        queue.consume()
                .subscribe()
                .with(item -> {
                    if (!received.isDone()) {
                        received.complete(item);
                    }
                }, received::completeExceptionally);
        return received;
    }

    private static NodeExecutionTask task(Map<String, Object> context) {
        return new NodeExecutionTask(
                RUN_ID,
                NODE_ID,
                1,
                null,
                context,
                RetryPolicy.none());
    }

    private static NodeExecutionTask taskWithQueueMetadata(
            int deliveryAttempt,
            int deferCount,
            Instant firstSeen,
            String lastDeferReason) {
        return taskWithQueueMetadata(
                deliveryAttempt,
                deferCount,
                firstSeen.toString(),
                lastDeferReason);
    }

    private static NodeExecutionTask taskWithQueueMetadata(
            Object deliveryAttempt,
            Object deferCount,
            String firstSeen,
            String lastDeferReason) {
        Map<String, Object> context = new HashMap<>();
        context.put(NodeExecutionTask.NODE_TYPE_KEY, "agent");
        context.put(TaskQueueMetadata.DELIVERY_ATTEMPT_KEY, deliveryAttempt);
        context.put(TaskQueueMetadata.DEFER_COUNT_KEY, deferCount);
        context.put(TaskQueueMetadata.FIRST_SEEN_AT_KEY, firstSeen);
        context.put(TaskQueueMetadata.LAST_DEFER_REASON_KEY, lastDeferReason);
        return task(context);
    }
}
