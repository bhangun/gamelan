package tech.kayys.gamelan.runtime.repository;

import static tech.kayys.gamelan.engine.executor.ExecutorSelectionRejectionReasons.CAPACITY_SATURATED;
import static tech.kayys.gamelan.engine.executor.ExecutorSelectionRejectionReasons.INVALID_CAPACITY_METADATA;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tech.kayys.gamelan.engine.node.NodeExecutionTask;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.run.RetryPolicy;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;
import tech.kayys.gamelan.scheduler.TaskDeadLetterQueue;
import tech.kayys.gamelan.scheduler.contract.TaskDeadLetterQueueContract;

class FileTaskDeadLetterQueueTest implements TaskDeadLetterQueueContract {

    private static final WorkflowRunId RUN_ID = WorkflowRunId.of("run-1");
    private static final NodeId NODE_ID = NodeId.of("node-1");

    @TempDir
    java.nio.file.Path tempDir;

    @Override
    public TaskDeadLetterQueue newTaskDeadLetterQueue() {
        return new FileTaskDeadLetterQueue(tempDir);
    }

    @Test
    void persistsDeadLettersAcrossInstancesAndSupportsLookupDeleteAndClear() {
        FileTaskDeadLetterQueue writer = new FileTaskDeadLetterQueue(tempDir);
        writer.publish(deadLetter("message-1", CAPACITY_SATURATED, RUN_ID, NODE_ID, "tenant-a", 10))
                .await().indefinitely();
        writer.publish(deadLetter("message-2", INVALID_CAPACITY_METADATA, RUN_ID, NODE_ID, "tenant-a", 20))
                .await().indefinitely();

        FileTaskDeadLetterQueue reader = new FileTaskDeadLetterQueue(tempDir);

        assertEquals(2L, reader.count().await().indefinitely());
        assertEquals("message-2", reader.list(1).await().indefinitely().getFirst().messageId());
        assertEquals("message-1", reader.get(" message-1 ").await().indefinitely().orElseThrow().messageId());
        assertEquals(1L, reader.count(new TaskDeadLetterQueue.DeadLetterQuery(
                100,
                RUN_ID.value(),
                NODE_ID.value(),
                "tenant-a",
                CAPACITY_SATURATED)).await().indefinitely());

        assertTrue(reader.delete("message-1").await().indefinitely());
        assertTrue(reader.get("message-1").await().indefinitely().isEmpty());
        assertFalse(reader.delete("missing").await().indefinitely());
        assertEquals(1L, reader.count().await().indefinitely());

        reader.clear().await().indefinitely();

        assertEquals(List.of(), reader.list(100).await().indefinitely());
        assertEquals(0L, reader.count().await().indefinitely());
    }

    @Test
    void hashesMessageIdsToAvoidPathTraversal() {
        String outsideName = "dead-letter-outside-" + System.nanoTime();
        String pathLikeMessageId = "../../" + outsideName + "/message";
        FileTaskDeadLetterQueue queue = new FileTaskDeadLetterQueue(tempDir);

        queue.publish(deadLetter(pathLikeMessageId, CAPACITY_SATURATED, RUN_ID, NODE_ID, "tenant-a", 10))
                .await().indefinitely();

        assertEquals(pathLikeMessageId, queue.get(pathLikeMessageId).await().indefinitely().orElseThrow().messageId());
        assertTrue(Files.isRegularFile(tempDir.resolve(FilePersistenceSupport.fileName(pathLikeMessageId))));
        assertFalse(Files.exists(tempDir.getParent().resolve(outsideName)));
    }

    @Test
    void clearWithQueryDeletesOnlyMatchingFiles() {
        FileTaskDeadLetterQueue queue = new FileTaskDeadLetterQueue(tempDir);
        queue.publish(deadLetter("message-1", CAPACITY_SATURATED, RUN_ID, NODE_ID, "tenant-a", 10))
                .await().indefinitely();
        queue.publish(deadLetter("message-2", INVALID_CAPACITY_METADATA, RUN_ID, NODE_ID, "tenant-a", 20))
                .await().indefinitely();
        queue.publish(deadLetter(
                "message-3",
                CAPACITY_SATURATED,
                WorkflowRunId.of("run-2"),
                NODE_ID,
                "tenant-b",
                30)).await().indefinitely();

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
