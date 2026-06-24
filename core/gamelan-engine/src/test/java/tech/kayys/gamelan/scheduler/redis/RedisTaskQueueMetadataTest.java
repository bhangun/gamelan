package tech.kayys.gamelan.scheduler.redis;

import static tech.kayys.gamelan.engine.executor.ExecutorSelectionRejectionReasons.CAPACITY_SATURATED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tech.kayys.gamelan.engine.node.NodeExecutionTask;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.run.RetryPolicy;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;
import tech.kayys.gamelan.scheduler.TaskQueue;
import tech.kayys.gamelan.scheduler.TaskQueueMetadata;

class RedisTaskQueueMetadataTest {

    private static final Instant FIRST_SEEN = Instant.parse("2026-05-29T00:00:00Z");
    private static final Instant NOW = Instant.parse("2026-05-29T00:01:00Z");

    @Test
    void deliveryPayloadAddsQueueMetadataToLegacyTasks() {
        NodeExecutionTask task = task(Map.of(NodeExecutionTask.NODE_TYPE_KEY, "agent"));

        NodeExecutionTask delivered = RedisTaskQueue.deliveryPayload(task, FIRST_SEEN);

        assertEquals(1, delivered.context().get(TaskQueueMetadata.DELIVERY_ATTEMPT_KEY));
        assertEquals(0, delivered.context().get(TaskQueueMetadata.DEFER_COUNT_KEY));
        assertEquals(FIRST_SEEN.toString(), delivered.context().get(TaskQueueMetadata.FIRST_SEEN_AT_KEY));
        assertEquals("agent", delivered.context().get(NodeExecutionTask.NODE_TYPE_KEY));
        assertEquals(task.runId(), delivered.runId());
        assertEquals(task.nodeId(), delivered.nodeId());
        assertEquals(task.attempt(), delivered.attempt());
        assertSame(task.token(), delivered.token());
        assertEquals(task.retryPolicy(), delivered.retryPolicy());
    }

    @Test
    void deliveryPayloadPreservesExistingQueueMetadata() {
        NodeExecutionTask task = taskWithQueueMetadata(4, 2, FIRST_SEEN, CAPACITY_SATURATED);

        NodeExecutionTask delivered = RedisTaskQueue.deliveryPayload(task, NOW);

        assertEquals(4, delivered.context().get(TaskQueueMetadata.DELIVERY_ATTEMPT_KEY));
        assertEquals(2, delivered.context().get(TaskQueueMetadata.DEFER_COUNT_KEY));
        assertEquals(FIRST_SEEN.toString(), delivered.context().get(TaskQueueMetadata.FIRST_SEEN_AT_KEY));
        assertEquals(CAPACITY_SATURATED, delivered.context().get(TaskQueueMetadata.LAST_DEFER_REASON_KEY));
    }

    @Test
    void queuedTaskNormalizesLegacyStreamPayloads() {
        TaskQueue.QueuedTask queuedTask = RedisTaskQueue.queuedTask(
                " redis-message-1 ",
                task(Map.of(NodeExecutionTask.NODE_TYPE_KEY, "agent")),
                FIRST_SEEN);

        assertEquals("redis-message-1", queuedTask.messageId());
        assertEquals(1, queuedTask.deliveryAttempt());
        assertEquals(0, queuedTask.deferCount());
        assertEquals(FIRST_SEEN, queuedTask.firstSeenAt());
        assertEquals("redis-message-1", queuedTask.leaseId());
        assertEquals(NOW.plus(Duration.ofMinutes(5)), RedisTaskQueue.queuedTask(
                "redis-message-1",
                task(Map.of(NodeExecutionTask.NODE_TYPE_KEY, "agent")),
                NOW,
                false,
                Duration.ofMinutes(5)).leaseExpiresAt());
    }

    @Test
    void reclaimedQueuedTaskIncrementsDeliveryAttempt() {
        TaskQueue.QueuedTask queuedTask = RedisTaskQueue.queuedTask(
                "redis-message-1",
                taskWithQueueMetadata(4, 2, FIRST_SEEN, CAPACITY_SATURATED),
                NOW,
                true,
                Duration.ofMinutes(7));

        assertEquals(5, queuedTask.deliveryAttempt());
        assertEquals(2, queuedTask.deferCount());
        assertEquals(FIRST_SEEN, queuedTask.firstSeenAt());
        assertEquals(CAPACITY_SATURATED, queuedTask.lastDeferReason());
        assertEquals("redis-message-1", queuedTask.leaseId());
        assertEquals(NOW.plus(Duration.ofMinutes(7)), queuedTask.leaseExpiresAt());
    }

    @Test
    void reclaimedLegacyQueuedTaskStartsAtSecondDeliveryAttempt() {
        TaskQueue.QueuedTask queuedTask = RedisTaskQueue.queuedTask(
                "redis-message-1",
                task(Map.of(NodeExecutionTask.NODE_TYPE_KEY, "agent")),
                FIRST_SEEN,
                true);

        assertEquals(2, queuedTask.deliveryAttempt());
        assertEquals(0, queuedTask.deferCount());
        assertEquals(FIRST_SEEN, queuedTask.firstSeenAt());
    }

    @Test
    void redisLeaseRenewalCommandsTargetCurrentConsumerAndMessage() {
        List<String> pendingOwnerCommand = RedisTaskQueue.pendingOwnerCommand(" redis-message-1 ");
        List<String> renewLeaseCommand = RedisTaskQueue.renewLeaseCommand(" redis-message-1 ");

        assertEquals(RedisTaskQueue.STREAM_KEY, pendingOwnerCommand.get(0));
        assertEquals(RedisTaskQueue.GROUP_NAME, pendingOwnerCommand.get(1));
        assertEquals("redis-message-1", pendingOwnerCommand.get(2));
        assertEquals("redis-message-1", pendingOwnerCommand.get(3));
        assertEquals("1", pendingOwnerCommand.get(4));
        assertTrue(!pendingOwnerCommand.get(5).isBlank());

        assertEquals(RedisTaskQueue.STREAM_KEY, renewLeaseCommand.get(0));
        assertEquals(RedisTaskQueue.GROUP_NAME, renewLeaseCommand.get(1));
        assertTrue(!renewLeaseCommand.get(2).isBlank());
        assertEquals("0", renewLeaseCommand.get(3));
        assertEquals("redis-message-1", renewLeaseCommand.get(4));
    }

    private static NodeExecutionTask taskWithQueueMetadata(
            int deliveryAttempt,
            int deferCount,
            Instant firstSeen,
            String lastDeferReason) {
        Map<String, Object> context = new HashMap<>();
        context.put(NodeExecutionTask.NODE_TYPE_KEY, "agent");
        context.put(TaskQueueMetadata.DELIVERY_ATTEMPT_KEY, deliveryAttempt);
        context.put(TaskQueueMetadata.DEFER_COUNT_KEY, deferCount);
        context.put(TaskQueueMetadata.FIRST_SEEN_AT_KEY, firstSeen.toString());
        context.put(TaskQueueMetadata.LAST_DEFER_REASON_KEY, lastDeferReason);
        return task(context);
    }

    private static NodeExecutionTask task(Map<String, Object> context) {
        return new NodeExecutionTask(
                WorkflowRunId.of("redis-run"),
                NodeId.of("redis-node"),
                1,
                null,
                context,
                RetryPolicy.none());
    }
}
