package tech.kayys.gamelan.runtime.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tech.kayys.gamelan.engine.node.NodeExecutionTask;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.run.RetryPolicy;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;
import tech.kayys.gamelan.scheduler.TaskQueue;
import tech.kayys.gamelan.scheduler.contract.TaskQueueContract;

class FileTaskQueueTest implements TaskQueueContract {

    @TempDir
    Path tempDir;

    @Override
    public TaskQueue newTaskQueue() {
        return newFileTaskQueue();
    }

    @Test
    void persistsAvailableTasksAcrossInstancesAndAcknowledgesWithLease() throws Exception {
        FileTaskQueue writer = newFileTaskQueue();

        writer.enqueue(task("persisted-run", "persisted-node")).await().indefinitely();

        assertEquals(1, writer.storedTaskCount());

        FileTaskQueue reader = newFileTaskQueue();
        LinkedBlockingQueue<TaskQueue.QueuedTask> received = subscribe(reader);
        TaskQueue.QueuedTask queued = received.poll(2, TimeUnit.SECONDS);

        assertNotNull(queued);
        assertEquals("persisted-run", queued.task().runId().value());
        assertEquals("persisted-node", queued.task().nodeId().value());
        assertEquals(1, queued.deliveryAttempt());
        assertEquals(0, queued.deferCount());
        assertNotNull(queued.leaseId());
        assertNotNull(queued.leaseExpiresAt());
        assertEquals(1, reader.storedTaskCount());

        reader.acknowledge(queued).await().indefinitely();

        assertEquals(0, reader.storedTaskCount());
    }

    @Test
    void expiredLeaseRedeliversWithNewLeaseAndIncrementedDeliveryAttempt() throws Exception {
        FileTaskQueue queue = newFileTaskQueue();
        LinkedBlockingQueue<TaskQueue.QueuedTask> received = subscribe(queue);

        queue.enqueue(task()).await().indefinitely();
        TaskQueue.QueuedTask first = received.poll(2, TimeUnit.SECONDS);
        assertNotNull(first);

        int emitted = queue.emitClaimable(first.leaseExpiresAt().plusMillis(1));
        TaskQueue.QueuedTask second = received.poll(2, TimeUnit.SECONDS);

        assertEquals(1, emitted);
        assertNotNull(second);
        assertEquals(first.messageId(), second.messageId());
        assertNotEquals(first.leaseId(), second.leaseId());
        assertEquals(2, second.deliveryAttempt());
        assertEquals(0, second.deferCount());
        assertEquals(first.firstSeenAt(), second.firstSeenAt());
        assertTrue(second.leaseExpiresAt().isAfter(first.leaseExpiresAt()));
        assertEquals(1, queue.storedTaskCount());
    }

    @Test
    void staleLeaseAcknowledgementDoesNotRemoveRedeliveredTask() throws Exception {
        FileTaskQueue queue = newFileTaskQueue();
        LinkedBlockingQueue<TaskQueue.QueuedTask> received = subscribe(queue);

        queue.enqueue(task()).await().indefinitely();
        TaskQueue.QueuedTask first = received.poll(2, TimeUnit.SECONDS);
        assertNotNull(first);
        queue.emitClaimable(first.leaseExpiresAt().plusMillis(1));
        TaskQueue.QueuedTask second = received.poll(2, TimeUnit.SECONDS);
        assertNotNull(second);

        queue.acknowledge(first).await().indefinitely();

        assertEquals(1, queue.storedTaskCount());
        assertTrue(queue.renewLease(second, Duration.ofMinutes(10)).await().indefinitely());

        queue.acknowledge(second).await().indefinitely();

        assertEquals(0, queue.storedTaskCount());
    }

    @Test
    void renewLeaseExtendsCurrentLeaseAndRejectsStaleLease() throws Exception {
        FileTaskQueue queue = newFileTaskQueue();
        LinkedBlockingQueue<TaskQueue.QueuedTask> received = subscribe(queue);

        queue.enqueue(task()).await().indefinitely();
        TaskQueue.QueuedTask first = received.poll(2, TimeUnit.SECONDS);
        assertNotNull(first);

        assertTrue(queue.renewLease(first, Duration.ofHours(1)).await().indefinitely());
        assertEquals(0, queue.emitClaimable(first.leaseExpiresAt().plusMillis(1)));

        queue.emitClaimable(Instant.now().plus(Duration.ofHours(2)));
        TaskQueue.QueuedTask second = received.poll(2, TimeUnit.SECONDS);

        assertNotNull(second);
        assertNotEquals(first.leaseId(), second.leaseId());
        assertFalse(queue.renewLease(first, Duration.ofHours(1)).await().indefinitely());
    }

    @Test
    void externalAcknowledgeHashesPathLikeMessageIds() throws Exception {
        String outsideName = "task-queue-outside-" + System.nanoTime();
        FileTaskQueue queue = newFileTaskQueue();
        LinkedBlockingQueue<TaskQueue.QueuedTask> received = subscribe(queue);

        queue.enqueue(task()).await().indefinitely();
        TaskQueue.QueuedTask queued = received.poll(2, TimeUnit.SECONDS);
        assertNotNull(queued);

        queue.acknowledge("../../" + outsideName + "/message").await().indefinitely();

        assertFalse(Files.exists(tempDir.getParent().resolve(outsideName)));
        assertEquals(1, queue.storedTaskCount());

        queue.acknowledge(queued).await().indefinitely();

        assertEquals(0, queue.storedTaskCount());
    }

    @Test
    void unreadableRecordDoesNotBlockValidTaskDelivery() throws Exception {
        Path unreadableRecord = tempDir.resolve("000-unreadable.json");
        Files.writeString(
                unreadableRecord,
                "{not-json",
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
        FileTaskQueue queue = newFileTaskQueue();
        LinkedBlockingQueue<TaskQueue.QueuedTask> received = subscribe(queue);

        queue.enqueue(task()).await().indefinitely();
        TaskQueue.QueuedTask queued = received.poll(2, TimeUnit.SECONDS);

        assertNotNull(queued);
        assertEquals("lease-run", queued.task().runId().value());
        assertEquals(2, queue.storedTaskCount());
        assertTrue(Files.isRegularFile(unreadableRecord));

        queue.acknowledge(queued).await().indefinitely();

        assertEquals(1, queue.storedTaskCount());
        assertTrue(Files.isRegularFile(unreadableRecord));
    }

    @Test
    void claimScanHonorsConfiguredBatchSize() throws Exception {
        FileTaskQueue queue = newFileTaskQueue(2);
        for (int index = 0; index < 5; index++) {
            queue.enqueue(task("batch-run-" + index, "batch-node-" + index)).await().indefinitely();
        }

        LinkedBlockingQueue<TaskQueue.QueuedTask> received = subscribe(queue);

        assertNotNull(received.poll(2, TimeUnit.SECONDS));
        assertNotNull(received.poll(2, TimeUnit.SECONDS));
        assertNull(received.poll(100, TimeUnit.MILLISECONDS));

        assertEquals(2, queue.emitClaimable(Instant.now()));
        assertNotNull(received.poll(2, TimeUnit.SECONDS));
        assertNotNull(received.poll(2, TimeUnit.SECONDS));
        assertNull(received.poll(100, TimeUnit.MILLISECONDS));

        assertEquals(1, queue.emitClaimable(Instant.now()));
        assertNotNull(received.poll(2, TimeUnit.SECONDS));
        assertNull(received.poll(100, TimeUnit.MILLISECONDS));
    }

    @Test
    void statsReportAvailableLeasedExpiredAndUnreadableRecords() throws Exception {
        Path unreadableRecord = tempDir.resolve("000-unreadable.json");
        Files.writeString(
                unreadableRecord,
                "{not-json",
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
        FileTaskQueue queue = newFileTaskQueue();
        for (int index = 0; index < 3; index++) {
            queue.enqueue(task("stats-run-" + index, "stats-node-" + index)).await().indefinitely();
        }

        TaskQueue.QueueStats available = queue.stats().await().indefinitely();

        assertEquals(4, available.total());
        assertEquals(3, available.available());
        assertEquals(0, available.leased());
        assertEquals(0, available.expired());
        assertEquals(1, available.unreadable());
        assertEquals(3, available.claimable());

        LinkedBlockingQueue<TaskQueue.QueuedTask> received = subscribe(queue);
        TaskQueue.QueuedTask first = received.poll(2, TimeUnit.SECONDS);
        assertNotNull(first);
        assertNotNull(received.poll(2, TimeUnit.SECONDS));
        assertNotNull(received.poll(2, TimeUnit.SECONDS));

        TaskQueue.QueueStats leased = queue.stats().await().indefinitely();

        assertEquals(4, leased.total());
        assertEquals(0, leased.available());
        assertEquals(3, leased.leased());
        assertEquals(0, leased.expired());
        assertEquals(1, leased.unreadable());
        assertEquals(0, leased.claimable());

        TaskQueue.QueueStats expired = queue.stats(first.leaseExpiresAt().plusMillis(1));

        assertEquals(4, expired.total());
        assertEquals(0, expired.available());
        assertEquals(0, expired.leased());
        assertEquals(3, expired.expired());
        assertEquals(1, expired.unreadable());
        assertEquals(3, expired.claimable());
    }

    private FileTaskQueue newFileTaskQueue() {
        return newFileTaskQueue(100);
    }

    private FileTaskQueue newFileTaskQueue(int claimBatchSize) {
        return new FileTaskQueue(
                tempDir,
                FilePersistenceSupport.objectMapper(),
                Duration.ofMinutes(5),
                Duration.ofDays(1),
                claimBatchSize);
    }

    private static LinkedBlockingQueue<TaskQueue.QueuedTask> subscribe(TaskQueue queue) {
        LinkedBlockingQueue<TaskQueue.QueuedTask> received = new LinkedBlockingQueue<>();
        queue.consume().subscribe().with(received::add);
        return received;
    }

    private static NodeExecutionTask task() {
        return task("lease-run", "lease-node");
    }

    private static NodeExecutionTask task(String runId, String nodeId) {
        return new NodeExecutionTask(
                WorkflowRunId.of(runId),
                NodeId.of(nodeId),
                1,
                null,
                Map.of(NodeExecutionTask.NODE_TYPE_KEY, "agent"),
                RetryPolicy.none());
    }
}
