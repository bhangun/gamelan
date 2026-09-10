package tech.kayys.gamelan.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import tech.kayys.gamelan.engine.node.NodeExecutionTask;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.run.RetryPolicy;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;
import tech.kayys.gamelan.scheduler.contract.TaskQueueContract;

class InMemoryTaskQueueTest implements TaskQueueContract {

    @Override
    public TaskQueue newTaskQueue() {
        return new InMemoryTaskQueue();
    }

    @Test
    void expiredLeaseRedeliversWithNewLeaseAndIncrementedDeliveryAttempt() throws Exception {
        InMemoryTaskQueue queue = new InMemoryTaskQueue(Duration.ofMinutes(5));
        LinkedBlockingQueue<TaskQueue.QueuedTask> received = subscribe(queue);

        queue.enqueue(task()).await().indefinitely();
        TaskQueue.QueuedTask first = received.poll(2, TimeUnit.SECONDS);
        assertNotNull(first);

        int expired = queue.expireLeases(first.leaseExpiresAt().plusMillis(1));
        TaskQueue.QueuedTask second = received.poll(2, TimeUnit.SECONDS);

        assertEquals(1, expired);
        assertNotNull(second);
        assertEquals(first.messageId(), second.messageId());
        assertNotEquals(first.leaseId(), second.leaseId());
        assertEquals(2, second.deliveryAttempt());
        assertEquals(0, second.deferCount());
        assertEquals(first.firstSeenAt(), second.firstSeenAt());
        assertTrue(second.leaseExpiresAt().isAfter(first.leaseExpiresAt()));
        assertEquals(1, queue.inFlightCount());
    }

    @Test
    void staleLeaseAcknowledgementDoesNotRemoveRedeliveredTask() throws Exception {
        InMemoryTaskQueue queue = new InMemoryTaskQueue(Duration.ofMinutes(5));
        LinkedBlockingQueue<TaskQueue.QueuedTask> received = subscribe(queue);

        queue.enqueue(task()).await().indefinitely();
        TaskQueue.QueuedTask first = received.poll(2, TimeUnit.SECONDS);
        assertNotNull(first);
        queue.expireLeases(first.leaseExpiresAt().plusMillis(1));
        TaskQueue.QueuedTask second = received.poll(2, TimeUnit.SECONDS);
        assertNotNull(second);

        queue.acknowledge(first).await().indefinitely();
        assertEquals(1, queue.inFlightCount());

        queue.acknowledge(second).await().indefinitely();
        assertEquals(0, queue.inFlightCount());
    }

    @Test
    void renewLeaseExtendsCurrentLeaseAndRejectsStaleLease() throws Exception {
        InMemoryTaskQueue queue = new InMemoryTaskQueue(Duration.ofMinutes(5));
        LinkedBlockingQueue<TaskQueue.QueuedTask> received = subscribe(queue);

        queue.enqueue(task()).await().indefinitely();
        TaskQueue.QueuedTask first = received.poll(2, TimeUnit.SECONDS);
        assertNotNull(first);

        assertTrue(queue.renewLease(first, Duration.ofHours(1)).await().indefinitely());
        assertEquals(0, queue.expireLeases(first.leaseExpiresAt().plusMillis(1)));
        assertEquals(1, queue.inFlightCount());

        queue.expireLeases(Instant.now().plus(Duration.ofHours(2)));
        TaskQueue.QueuedTask second = received.poll(2, TimeUnit.SECONDS);
        assertNotNull(second);
        assertNotEquals(first.leaseId(), second.leaseId());
        assertFalse(queue.renewLease(first, Duration.ofHours(1)).await().indefinitely());
    }

    @Test
    void statsReportLeasedAndExpiredInFlightTasks() throws Exception {
        InMemoryTaskQueue queue = new InMemoryTaskQueue(Duration.ofMinutes(5));
        LinkedBlockingQueue<TaskQueue.QueuedTask> received = subscribe(queue);

        queue.enqueue(task()).await().indefinitely();
        TaskQueue.QueuedTask first = received.poll(2, TimeUnit.SECONDS);
        assertNotNull(first);

        TaskQueue.QueueStats leased = queue.stats().await().indefinitely();

        assertTrue(leased.known());
        assertEquals(1, leased.total());
        assertEquals(0, leased.available());
        assertEquals(1, leased.leased());
        assertEquals(0, leased.expired());
        assertEquals(0, leased.unreadable());
        assertEquals(0, leased.claimable());

        TaskQueue.QueueStats expired = queue.stats(first.leaseExpiresAt().plusMillis(1));

        assertTrue(expired.known());
        assertEquals(1, expired.total());
        assertEquals(0, expired.available());
        assertEquals(0, expired.leased());
        assertEquals(1, expired.expired());
        assertEquals(0, expired.unreadable());
        assertEquals(1, expired.claimable());
    }

    private static LinkedBlockingQueue<TaskQueue.QueuedTask> subscribe(TaskQueue queue) {
        LinkedBlockingQueue<TaskQueue.QueuedTask> received = new LinkedBlockingQueue<>();
        queue.consume().subscribe().with(received::add);
        return received;
    }

    private static NodeExecutionTask task() {
        return new NodeExecutionTask(
                WorkflowRunId.of("lease-run"),
                NodeId.of("lease-node"),
                1,
                null,
                Map.of(NodeExecutionTask.NODE_TYPE_KEY, "agent"),
                RetryPolicy.none());
    }
}
