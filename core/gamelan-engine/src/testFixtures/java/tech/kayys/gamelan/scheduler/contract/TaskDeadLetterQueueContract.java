package tech.kayys.gamelan.scheduler.contract;

import static tech.kayys.gamelan.engine.executor.ExecutorSelectionRejectionReasons.CAPACITY_SATURATED;
import static tech.kayys.gamelan.engine.executor.ExecutorSelectionRejectionReasons.INVALID_CAPACITY_METADATA;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tech.kayys.gamelan.engine.node.NodeExecutionTask;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.run.RetryPolicy;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;
import tech.kayys.gamelan.scheduler.TaskDeadLetterQueue;

/**
 * Shared conformance tests for task dead-letter persistence and operator queries.
 */
public interface TaskDeadLetterQueueContract {

    WorkflowRunId RUN_ID = WorkflowRunId.of("contract-run-1");
    NodeId NODE_ID = NodeId.of("contract-node-1");

    TaskDeadLetterQueue newTaskDeadLetterQueue();

    @Test
    default void deadLetterQueueContract_listsCountsGetsDeletesAndClearsEntries() {
        TaskDeadLetterQueue queue = newTaskDeadLetterQueue();
        queue.publish(deadLetter("contract-message-1", CAPACITY_SATURATED, RUN_ID, NODE_ID, "tenant-a", 10))
                .await()
                .indefinitely();
        queue.publish(deadLetter("contract-message-2", INVALID_CAPACITY_METADATA, RUN_ID, NODE_ID, "tenant-a", 20))
                .await()
                .indefinitely();

        assertEquals(2L, queue.count().await().indefinitely());
        assertEquals("contract-message-2", queue.list(1).await().indefinitely().getFirst().messageId());
        assertEquals("contract-message-1", queue.get(" contract-message-1 ").await().indefinitely()
                .orElseThrow()
                .messageId());
        assertEquals(1L, queue.count(new TaskDeadLetterQueue.DeadLetterQuery(
                100,
                RUN_ID.value(),
                NODE_ID.value(),
                "tenant-a",
                CAPACITY_SATURATED)).await().indefinitely());

        assertFalse(queue.delete(" ").await().indefinitely());
        assertTrue(queue.delete(" contract-message-1 ").await().indefinitely());
        assertTrue(queue.get("contract-message-1").await().indefinitely().isEmpty());
        assertFalse(queue.delete("missing-message").await().indefinitely());
        assertEquals(1L, queue.count().await().indefinitely());

        queue.clear().await().indefinitely();

        assertEquals(List.of(), queue.list(100).await().indefinitely());
        assertEquals(0L, queue.count().await().indefinitely());
    }

    @Test
    default void deadLetterQueueContract_filtersAndClearsByRunNodeTenantAndReason() {
        TaskDeadLetterQueue queue = newTaskDeadLetterQueue();
        queue.publish(deadLetter("contract-message-1", CAPACITY_SATURATED, RUN_ID, NODE_ID, "tenant-a", 10))
                .await()
                .indefinitely();
        queue.publish(deadLetter("contract-message-2", INVALID_CAPACITY_METADATA, RUN_ID, NODE_ID, "tenant-a", 20))
                .await()
                .indefinitely();
        queue.publish(deadLetter(
                "contract-message-3",
                CAPACITY_SATURATED,
                WorkflowRunId.of("contract-run-2"),
                NODE_ID,
                "tenant-b",
                30)).await().indefinitely();

        assertEquals(1L, queue.count(new TaskDeadLetterQueue.DeadLetterQuery(
                100,
                null,
                null,
                "tenant-a",
                CAPACITY_SATURATED)).await().indefinitely());
        assertEquals("contract-message-3", queue.list(new TaskDeadLetterQueue.DeadLetterQuery(
                100,
                null,
                NODE_ID.value(),
                "tenant-b",
                CAPACITY_SATURATED)).await().indefinitely().getFirst().messageId());
        assertTrue(queue.list(new TaskDeadLetterQueue.DeadLetterQuery(
                100,
                RUN_ID.value(),
                NODE_ID.value(),
                "tenant-b",
                null)).await().indefinitely().isEmpty());

        long deleted = queue.clear(new TaskDeadLetterQueue.DeadLetterQuery(
                100,
                null,
                null,
                "tenant-a",
                CAPACITY_SATURATED)).await().indefinitely();

        assertEquals(1L, deleted);
        assertTrue(queue.get("contract-message-1").await().indefinitely().isEmpty());
        assertEquals(2L, queue.count().await().indefinitely());
        assertEquals("contract-message-3", queue.list(10).await().indefinitely().getFirst().messageId());
    }

    @Test
    default void deadLetterQueueContract_normalizesTaskDefaultsAfterPersistence() {
        TaskDeadLetterQueue queue = newTaskDeadLetterQueue();
        queue.publish(new TaskDeadLetterQueue.DeadLetterTask(
                "contract-default-message",
                task(RUN_ID, NODE_ID, "tenant-a"),
                " ",
                0,
                -1,
                null,
                null,
                null)).await().indefinitely();

        TaskDeadLetterQueue.DeadLetterTask restored = queue.get("contract-default-message")
                .await()
                .indefinitely()
                .orElseThrow();

        assertEquals("unknown", restored.reason());
        assertEquals(1, restored.deliveryAttempt());
        assertEquals(0, restored.deferCount());
        assertTrue(restored.firstSeenAt().isBefore(Instant.now().plusSeconds(1)));
        assertTrue(restored.deadLetteredAt().isBefore(Instant.now().plusSeconds(1)));
        assertEquals(Map.of(), restored.diagnostics());
    }

    private static TaskDeadLetterQueue.DeadLetterTask deadLetter(
            String messageId,
            String reason,
            WorkflowRunId runId,
            NodeId nodeId,
            String tenantId,
            long deadLetteredAtSecond) {
        return new TaskDeadLetterQueue.DeadLetterTask(
                messageId,
                task(runId, nodeId, tenantId),
                reason,
                2,
                1,
                Instant.ofEpochSecond(1),
                Instant.ofEpochSecond(deadLetteredAtSecond),
                Map.of("selectionReason", reason));
    }

    private static NodeExecutionTask task(
            WorkflowRunId runId,
            NodeId nodeId,
            String tenantId) {
        Map<String, Object> context = new HashMap<>();
        context.put(NodeExecutionTask.NODE_TYPE_KEY, "agent");
        context.put(NodeExecutionTask.TENANT_ID_KEY, tenantId);
        return new NodeExecutionTask(
                runId,
                nodeId,
                1,
                null,
                context,
                RetryPolicy.none());
    }
}
