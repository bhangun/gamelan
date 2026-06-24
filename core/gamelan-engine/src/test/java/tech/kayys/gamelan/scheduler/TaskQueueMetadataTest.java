package tech.kayys.gamelan.scheduler;

import static tech.kayys.gamelan.engine.executor.ExecutorSelectionRejectionReasons.CAPACITY_SATURATED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tech.kayys.gamelan.engine.node.NodeExecutionTask;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.run.RetryPolicy;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;

class TaskQueueMetadataTest {

    private static final WorkflowRunId RUN_ID = WorkflowRunId.of("metadata-run");
    private static final NodeId NODE_ID = NodeId.of("metadata-node");
    private static final Instant FIRST_SEEN = Instant.parse("2026-05-27T00:00:00Z");

    @Test
    void readsNormalizedDeliveryMetadata() {
        NodeExecutionTask task = taskWithMetadata("4", "-1", FIRST_SEEN.toString(), " " + CAPACITY_SATURATED + " ");

        assertEquals(4, TaskQueueMetadata.deliveryAttempt(task));
        assertEquals(0, TaskQueueMetadata.deferCount(task));
        assertEquals(FIRST_SEEN, TaskQueueMetadata.firstSeenAt(task));
        assertEquals(CAPACITY_SATURATED, TaskQueueMetadata.lastDeferReason(task));
    }

    @Test
    void deferredTaskIncrementsDeliveryMetadataAndPreservesDomainContext() {
        NodeExecutionTask task = taskWithMetadata(2, 1, FIRST_SEEN.toString(), "previous");

        NodeExecutionTask deferred = TaskQueueMetadata.deferredTask(
                new TaskQueue.QueuedTask("message-1", task),
                " " + CAPACITY_SATURATED + " ");

        assertEquals(3, deferred.context().get(TaskQueueMetadata.DELIVERY_ATTEMPT_KEY));
        assertEquals(2, deferred.context().get(TaskQueueMetadata.DEFER_COUNT_KEY));
        assertEquals(FIRST_SEEN.toString(), deferred.context().get(TaskQueueMetadata.FIRST_SEEN_AT_KEY));
        assertEquals(CAPACITY_SATURATED, deferred.context().get(TaskQueueMetadata.LAST_DEFER_REASON_KEY));
        assertEquals("agent", deferred.context().get(NodeExecutionTask.NODE_TYPE_KEY));
    }

    @Test
    void deliveryTaskInitializesMissingDeliveryMetadata() {
        NodeExecutionTask task = new NodeExecutionTask(
                RUN_ID,
                NODE_ID,
                1,
                null,
                Map.of(NodeExecutionTask.NODE_TYPE_KEY, "agent"),
                RetryPolicy.none());

        NodeExecutionTask delivered = TaskQueueMetadata.deliveryTask(task, FIRST_SEEN);

        assertEquals(1, delivered.context().get(TaskQueueMetadata.DELIVERY_ATTEMPT_KEY));
        assertEquals(0, delivered.context().get(TaskQueueMetadata.DEFER_COUNT_KEY));
        assertEquals(FIRST_SEEN.toString(), delivered.context().get(TaskQueueMetadata.FIRST_SEEN_AT_KEY));
        assertEquals("agent", delivered.context().get(NodeExecutionTask.NODE_TYPE_KEY));
    }

    @Test
    void redeliveredTaskIncrementsDeliveryAttemptWithoutChangingDeferCount() {
        NodeExecutionTask task = taskWithMetadata(2, 1, FIRST_SEEN.toString(), CAPACITY_SATURATED);

        NodeExecutionTask redelivered = TaskQueueMetadata.redeliveredTask(
                new TaskQueue.QueuedTask("message-1", task));

        assertEquals(3, redelivered.context().get(TaskQueueMetadata.DELIVERY_ATTEMPT_KEY));
        assertEquals(1, redelivered.context().get(TaskQueueMetadata.DEFER_COUNT_KEY));
        assertEquals(FIRST_SEEN.toString(), redelivered.context().get(TaskQueueMetadata.FIRST_SEEN_AT_KEY));
        assertEquals(CAPACITY_SATURATED, redelivered.context().get(TaskQueueMetadata.LAST_DEFER_REASON_KEY));
    }

    @Test
    void withoutQueueMetadataPreservesTaskIdentityAndDomainContext() {
        NodeExecutionTask task = taskWithMetadata(3, 2, FIRST_SEEN.toString(), CAPACITY_SATURATED);

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

    @Test
    void invalidValuesFallbackSafely() {
        NodeExecutionTask task = taskWithMetadata("bad", "bad", "not-an-instant", " ");

        assertEquals(1, TaskQueueMetadata.deliveryAttempt(task));
        assertEquals(0, TaskQueueMetadata.deferCount(task));
        assertTrue(TaskQueueMetadata.firstSeenAt(task).isBefore(Instant.now().plusSeconds(1)));
        assertEquals("", TaskQueueMetadata.lastDeferReason(task));
    }

    private static NodeExecutionTask taskWithMetadata(
            Object deliveryAttempt,
            Object deferCount,
            String firstSeenAt,
            String lastDeferReason) {
        Map<String, Object> context = new HashMap<>();
        context.put(NodeExecutionTask.NODE_TYPE_KEY, "agent");
        context.put(TaskQueueMetadata.DELIVERY_ATTEMPT_KEY, deliveryAttempt);
        context.put(TaskQueueMetadata.DEFER_COUNT_KEY, deferCount);
        context.put(TaskQueueMetadata.FIRST_SEEN_AT_KEY, firstSeenAt);
        context.put(TaskQueueMetadata.LAST_DEFER_REASON_KEY, lastDeferReason);
        return new NodeExecutionTask(
                RUN_ID,
                NODE_ID,
                1,
                null,
                context,
                RetryPolicy.none());
    }
}
