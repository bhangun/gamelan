package tech.kayys.gamelan.runtime.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.engine.node.NodeExecutionTask;
import tech.kayys.gamelan.scheduler.TaskQueue;

class TaskQueueResourceTest {

    @Test
    void statsDelegatesToActiveTaskQueue() {
        RecordingTaskQueue taskQueue = new RecordingTaskQueue(TaskQueue.QueueStats.known(10, 3, 4, 2, 1));
        TaskQueueResource resource = new TaskQueueResource();
        resource.taskQueue = taskQueue;

        TaskQueue.QueueStats stats = resource.stats().await().indefinitely();

        assertEquals(1, taskQueue.statsCalls);
        assertTrue(stats.known());
        assertEquals(10, stats.total());
        assertEquals(3, stats.available());
        assertEquals(4, stats.leased());
        assertEquals(2, stats.expired());
        assertEquals(1, stats.unreadable());
        assertEquals(5, stats.claimable());
        assertEquals(TaskQueue.QueueHealth.UNREADABLE_RECORDS, stats.health());
    }

    @Test
    void statsCanReportUnknownForQueuesWithoutNativeInspection() {
        RecordingTaskQueue taskQueue = new RecordingTaskQueue(TaskQueue.QueueStats.unknown());
        TaskQueueResource resource = new TaskQueueResource();
        resource.taskQueue = taskQueue;

        TaskQueue.QueueStats stats = resource.stats().await().indefinitely();

        assertEquals(1, taskQueue.statsCalls);
        assertFalse(stats.known());
        assertEquals(TaskQueue.QueueHealth.UNKNOWN, stats.health());
        assertEquals(-1, stats.total());
    }

    private static final class RecordingTaskQueue implements TaskQueue {
        private final TaskQueue.QueueStats stats;
        private int statsCalls;

        private RecordingTaskQueue(TaskQueue.QueueStats stats) {
            this.stats = stats;
        }

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
            return Uni.createFrom().voidItem();
        }

        @Override
        public Uni<Boolean> renewLease(QueuedTask queuedTask, Duration leaseDuration) {
            return Uni.createFrom().item(false);
        }

        @Override
        public Uni<TaskQueue.QueueStats> stats() {
            statsCalls++;
            return Uni.createFrom().item(stats);
        }
    }
}
