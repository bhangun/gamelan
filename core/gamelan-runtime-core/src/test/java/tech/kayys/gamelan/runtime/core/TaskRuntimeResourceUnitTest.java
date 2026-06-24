package tech.kayys.gamelan.runtime.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.core.Response;
import tech.kayys.gamelan.engine.node.NodeExecutionTask;
import tech.kayys.gamelan.runtime.resource.TaskRuntimeResource;
import tech.kayys.gamelan.scheduler.TaskDeadLetterQueue;
import tech.kayys.gamelan.scheduler.TaskQueue;
import tech.kayys.gamelan.scheduler.TaskWorker;

class TaskRuntimeResourceUnitTest {

    @Test
    void statusAggregatesWorkerQueueAndDeadLetters() throws ReflectiveOperationException {
        TaskRuntimeResource resource = new TaskRuntimeResource();
        RecordingTaskQueue queue = new RecordingTaskQueue(TaskQueue.QueueStats.known(12, 4, 5, 0, 0));
        RecordingDeadLetterQueue deadLetters = new RecordingDeadLetterQueue(7L);
        set(resource, "taskWorker", new TaskWorker());
        set(resource, "taskQueue", queue);
        set(resource, "deadLetterQueue", deadLetters);

        TaskRuntimeResource.TaskRuntimeStatus status = resource.status().await().indefinitely();

        assertEquals(TaskWorker.WorkerState.STOPPED, status.worker().state());
        assertTrue(status.queue().available());
        assertEquals(12, status.queue().value().total());
        assertEquals(TaskQueue.QueueHealth.BACKLOG, status.queue().value().health());
        assertNull(status.queue().error());
        assertFalse(status.queue().timedOut());
        assertTrue(status.queue().durationMillis() >= 0);
        assertNotNull(status.queue().observedAt());
        assertTrue(status.deadLetters().available());
        assertEquals(7L, status.deadLetters().value());
        assertNull(status.deadLetters().error());
        assertFalse(status.deadLetters().timedOut());
        assertTrue(status.deadLetters().durationMillis() >= 0);
        assertNotNull(status.deadLetters().observedAt());
        assertEquals(TaskRuntimeResource.TaskRuntimeHealth.DEGRADED, status.health());
        assertIssue(status, "worker-stopped");
        assertIssue(status, "queue-backlog");
        assertEquals(1, queue.statsCalls);
        assertEquals(1, deadLetters.countCalls);
        assertFalse(status.cache().enabled());
        assertFalse(status.cache().hit());
        assertNotNull(status.observedAt());
    }

    @Test
    void statusKeepsWorkerSnapshotWhenBackingStoresFail() throws ReflectiveOperationException {
        TaskRuntimeResource resource = new TaskRuntimeResource();
        RecordingTaskQueue queue = new RecordingTaskQueue(new IllegalStateException("queue unavailable"));
        RecordingDeadLetterQueue deadLetters = new RecordingDeadLetterQueue(
                new IllegalStateException("dead letters unavailable"));
        set(resource, "taskWorker", new TaskWorker());
        set(resource, "taskQueue", queue);
        set(resource, "deadLetterQueue", deadLetters);

        TaskRuntimeResource.TaskRuntimeStatus status = resource.status().await().indefinitely();

        assertEquals(TaskWorker.WorkerState.STOPPED, status.worker().state());
        assertFalse(status.queue().available());
        assertNull(status.queue().value());
        assertEquals("IllegalStateException: queue unavailable", status.queue().error());
        assertFalse(status.queue().timedOut());
        assertTrue(status.queue().durationMillis() >= 0);
        assertNotNull(status.queue().observedAt());
        assertFalse(status.deadLetters().available());
        assertNull(status.deadLetters().value());
        assertEquals("IllegalStateException: dead letters unavailable", status.deadLetters().error());
        assertFalse(status.deadLetters().timedOut());
        assertTrue(status.deadLetters().durationMillis() >= 0);
        assertNotNull(status.deadLetters().observedAt());
        assertEquals(TaskRuntimeResource.TaskRuntimeHealth.DEGRADED, status.health());
        assertIssue(status, "worker-stopped");
        assertIssue(status, "queue-unavailable");
        assertIssue(status, "dead-letter-unavailable");
        assertNotNull(status.observedAt());
    }

    @Test
    void statusTimesOutSlowBackingStores() throws ReflectiveOperationException {
        TaskRuntimeResource resource = resourceFor(
                workerStatus(TaskWorker.WorkerState.RUNNING, 0, 64, 0),
                new HangingTaskQueue(),
                new HangingDeadLetterQueue());
        set(resource, "statusComponentTimeout", Duration.ofMillis(10));

        TaskRuntimeResource.TaskRuntimeStatus status = resource.status().await().atMost(Duration.ofSeconds(2));

        assertFalse(status.queue().available());
        assertTrue(status.queue().error().contains("task queue stats timed out"));
        assertTrue(status.queue().timedOut());
        assertTrue(status.queue().durationMillis() >= 0);
        assertNotNull(status.queue().observedAt());
        assertFalse(status.deadLetters().available());
        assertTrue(status.deadLetters().error().contains("task dead-letter count timed out"));
        assertTrue(status.deadLetters().timedOut());
        assertTrue(status.deadLetters().durationMillis() >= 0);
        assertNotNull(status.deadLetters().observedAt());
        assertEquals(TaskRuntimeResource.TaskRuntimeHealth.DEGRADED, status.health());
        assertIssue(status, "queue-unavailable");
        assertIssue(status, "dead-letter-unavailable");
    }

    @Test
    void statusUsesCachedSnapshotWithinConfiguredTtl() throws ReflectiveOperationException {
        RecordingTaskQueue queue = new RecordingTaskQueue(TaskQueue.QueueStats.known(1, 0, 1, 0, 0));
        RecordingDeadLetterQueue deadLetters = new RecordingDeadLetterQueue(3L);
        TaskRuntimeResource resource = resourceFor(
                workerStatus(TaskWorker.WorkerState.RUNNING, 1, 63, 0),
                queue,
                deadLetters);
        set(resource, "statusCacheTtl", Duration.ofMinutes(1));

        TaskRuntimeResource.TaskRuntimeStatus first = resource.status().await().indefinitely();
        TaskRuntimeResource.TaskRuntimeStatus second = resource.status().await().indefinitely();
        Response readinessResponse = resource.readiness().await().indefinitely();

        assertEquals(1, queue.statsCalls);
        assertEquals(1, deadLetters.countCalls);
        assertTrue(first.cache().enabled());
        assertFalse(first.cache().hit());
        assertEquals(Duration.ofMinutes(1).toMillis(), first.cache().ttlMillis());
        assertEquals(0, first.cache().ageMillis());
        assertNotNull(first.cache().expiresAt());
        assertTrue(second.cache().enabled());
        assertTrue(second.cache().hit());
        assertEquals(first.observedAt(), second.observedAt());
        assertEquals(first.queue().observedAt(), second.queue().observedAt());
        assertEquals(Response.Status.OK.getStatusCode(), readinessResponse.getStatus());
        assertEquals(1, queue.statsCalls);
        assertEquals(1, deadLetters.countCalls);
    }

    @Test
    void statusClassifiesHealthyQueueStates() throws ReflectiveOperationException {
        TaskRuntimeResource.TaskRuntimeStatus idle = statusFor(
                workerStatus(TaskWorker.WorkerState.RUNNING, 0, 64, 0),
                TaskQueue.QueueStats.known(0, 0, 0, 0, 0));
        assertEquals(TaskRuntimeResource.TaskRuntimeHealth.IDLE, idle.health());
        assertTrue(idle.issues().isEmpty());

        TaskRuntimeResource.TaskRuntimeStatus active = statusFor(
                workerStatus(TaskWorker.WorkerState.RUNNING, 2, 62, 0),
                TaskQueue.QueueStats.known(2, 0, 2, 0, 0));
        assertEquals(TaskRuntimeResource.TaskRuntimeHealth.ACTIVE, active.health());

        TaskRuntimeResource.TaskRuntimeStatus backlog = statusFor(
                workerStatus(TaskWorker.WorkerState.RUNNING, 0, 64, 0),
                TaskQueue.QueueStats.known(4, 4, 0, 0, 0));
        assertEquals(TaskRuntimeResource.TaskRuntimeHealth.BACKLOG, backlog.health());
        assertIssue(backlog, "queue-backlog");

        TaskRuntimeResource.TaskRuntimeStatus staleLeases = statusFor(
                workerStatus(TaskWorker.WorkerState.RUNNING, 0, 64, 0),
                TaskQueue.QueueStats.known(2, 0, 0, 2, 0));
        assertEquals(TaskRuntimeResource.TaskRuntimeHealth.STALE_LEASES, staleLeases.health());
        assertIssue(staleLeases, "queue-stale-leases");

        TaskRuntimeResource.TaskRuntimeStatus unknown = statusFor(
                workerStatus(TaskWorker.WorkerState.RUNNING, 0, 64, 0),
                TaskQueue.QueueStats.unknown());
        assertEquals(TaskRuntimeResource.TaskRuntimeHealth.UNKNOWN, unknown.health());
        assertIssue(unknown, "queue-stats-unknown");
    }

    @Test
    void statusClassifiesWorkerCapacityPressureAsBacklog() throws ReflectiveOperationException {
        TaskRuntimeResource.TaskRuntimeStatus status = statusFor(
                workerStatus(TaskWorker.WorkerState.RUNNING, 2, 0, 0),
                TaskQueue.QueueStats.known(2, 0, 2, 0, 0));

        assertEquals(TaskRuntimeResource.TaskRuntimeHealth.BACKLOG, status.health());
        assertIssue(status, "worker-at-capacity");
    }

    @Test
    void statusClassifiesPausedAndDrainingWorkersAsDegraded() throws ReflectiveOperationException {
        TaskRuntimeResource.TaskRuntimeStatus paused = statusFor(
                workerStatus(TaskWorker.WorkerState.PAUSED, 0, 64, 0),
                TaskQueue.QueueStats.known(0, 0, 0, 0, 0));
        assertEquals(TaskRuntimeResource.TaskRuntimeHealth.DEGRADED, paused.health());
        assertIssue(paused, "worker-paused");

        TaskRuntimeResource.TaskRuntimeStatus draining = statusFor(
                workerStatus(TaskWorker.WorkerState.DRAINING, 1, 63, 0),
                TaskQueue.QueueStats.known(1, 0, 1, 0, 0));
        assertEquals(TaskRuntimeResource.TaskRuntimeHealth.DEGRADED, draining.health());
        assertIssue(draining, "worker-draining");
    }

    @Test
    void readinessReturnsOkForNonDegradedRuntime() throws ReflectiveOperationException {
        TaskRuntimeResource resource = resourceFor(
                workerStatus(TaskWorker.WorkerState.RUNNING, 0, 64, 0),
                new RecordingTaskQueue(TaskQueue.QueueStats.known(0, 0, 0, 0, 0)),
                new RecordingDeadLetterQueue(0L));

        Response response = resource.readiness().await().indefinitely();
        TaskRuntimeResource.TaskRuntimeReadiness readiness =
                (TaskRuntimeResource.TaskRuntimeReadiness) response.getEntity();

        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        assertTrue(readiness.ready());
        assertEquals(TaskRuntimeResource.TaskRuntimeHealth.IDLE, readiness.health());
        assertTrue(readiness.issueCodes().isEmpty());
        assertTrue(readiness.policy().acceptUnknown());
        assertTrue(readiness.policy().acceptStaleLeases());
        assertTrue(readiness.policy().acceptBacklog());
        assertNull(readiness.rejectionReason());
        assertNotNull(readiness.observedAt());
    }

    @Test
    void readinessReturnsServiceUnavailableForDegradedRuntime() throws ReflectiveOperationException {
        TaskRuntimeResource resource = resourceFor(
                workerStatus(TaskWorker.WorkerState.STOPPED, 0, 64, 0),
                new RecordingTaskQueue(TaskQueue.QueueStats.known(0, 0, 0, 0, 0)),
                new RecordingDeadLetterQueue(0L));

        Response response = resource.readiness().await().indefinitely();
        TaskRuntimeResource.TaskRuntimeReadiness readiness =
                (TaskRuntimeResource.TaskRuntimeReadiness) response.getEntity();

        assertEquals(Response.Status.SERVICE_UNAVAILABLE.getStatusCode(), response.getStatus());
        assertFalse(readiness.ready());
        assertEquals(TaskRuntimeResource.TaskRuntimeHealth.DEGRADED, readiness.health());
        assertTrue(readiness.issueCodes().contains("worker-stopped"));
        assertEquals("runtime-degraded", readiness.rejectionReason());
        assertNotNull(readiness.observedAt());
    }

    @Test
    void livenessDoesNotTouchBackingStores() throws ReflectiveOperationException {
        RecordingTaskQueue queue = new RecordingTaskQueue(new IllegalStateException("queue unavailable"));
        RecordingDeadLetterQueue deadLetters = new RecordingDeadLetterQueue(
                new IllegalStateException("dead letters unavailable"));
        TaskRuntimeResource resource = resourceFor(
                workerStatus(TaskWorker.WorkerState.STOPPED, 0, 64, 0),
                queue,
                deadLetters);

        TaskRuntimeResource.TaskRuntimeLiveness liveness = resource.liveness();

        assertTrue(liveness.alive());
        assertNotNull(liveness.observedAt());
        assertEquals(0, queue.statsCalls);
        assertEquals(0, deadLetters.countCalls);
    }

    @Test
    void readinessPolicyCanRejectUnknownStats() throws ReflectiveOperationException {
        TaskRuntimeResource resource = resourceFor(
                workerStatus(TaskWorker.WorkerState.RUNNING, 0, 64, 0),
                new RecordingTaskQueue(TaskQueue.QueueStats.unknown()),
                new RecordingDeadLetterQueue(0L));
        set(resource, "readinessAcceptUnknown", false);

        Response response = resource.readiness().await().indefinitely();
        TaskRuntimeResource.TaskRuntimeReadiness readiness =
                (TaskRuntimeResource.TaskRuntimeReadiness) response.getEntity();

        assertEquals(Response.Status.SERVICE_UNAVAILABLE.getStatusCode(), response.getStatus());
        assertFalse(readiness.ready());
        assertEquals(TaskRuntimeResource.TaskRuntimeHealth.UNKNOWN, readiness.health());
        assertFalse(readiness.policy().acceptUnknown());
        assertTrue(readiness.issueCodes().contains("queue-stats-unknown"));
        assertEquals("health-not-accepted-by-readiness-policy", readiness.rejectionReason());
    }

    private static void assertIssue(TaskRuntimeResource.TaskRuntimeStatus status, String code) {
        assertTrue(status.issues().stream().anyMatch(issue -> code.equals(issue.code())),
                "Expected issue code " + code + " in " + status.issues());
    }

    private static TaskRuntimeResource.TaskRuntimeStatus statusFor(
            TaskWorker.WorkerStatus worker,
            TaskQueue.QueueStats queueStats) throws ReflectiveOperationException {
        return resourceFor(
                worker,
                new RecordingTaskQueue(queueStats),
                new RecordingDeadLetterQueue(0L))
                .status().await().indefinitely();
    }

    private static TaskRuntimeResource resourceFor(
            TaskWorker.WorkerStatus worker,
            TaskQueue queue,
            TaskDeadLetterQueue deadLetters) throws ReflectiveOperationException {
        TaskRuntimeResource resource = new TaskRuntimeResource();
        set(resource, "taskWorker", new StaticTaskWorker(worker));
        set(resource, "taskQueue", queue);
        set(resource, "deadLetterQueue", deadLetters);
        return resource;
    }

    private static TaskWorker.WorkerStatus workerStatus(
            TaskWorker.WorkerState state,
            int inFlightCount,
            int remainingCapacity,
            long queueStreamFailures) {
        return new TaskWorker.WorkerStatus(
                state,
                state == TaskWorker.WorkerState.RUNNING,
                state == TaskWorker.WorkerState.PAUSED || state == TaskWorker.WorkerState.DRAINING,
                state == TaskWorker.WorkerState.PAUSED || state == TaskWorker.WorkerState.DRAINING,
                state == TaskWorker.WorkerState.RUNNING,
                "RecordingTaskQueue",
                "StaticDispatcher",
                Math.max(inFlightCount + remainingCapacity, 1),
                inFlightCount,
                remainingCapacity,
                false,
                false,
                "PT1S",
                30,
                100,
                "PT5M",
                "PT30S",
                "PT10S",
                "PT30S",
                "PT1S",
                "PT30S",
                0,
                0,
                0,
                0,
                queueStreamFailures,
                null,
                null,
                queueStreamFailures > 0 ? "IllegalStateException: stream failed" : null,
                queueStreamFailures > 0 ? Instant.now() : null,
                Instant.now(),
                List.of());
    }

    private static void set(Object target, String fieldName, Object value) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static final class StaticTaskWorker extends TaskWorker {
        private final TaskWorker.WorkerStatus status;

        private StaticTaskWorker(TaskWorker.WorkerStatus status) {
            this.status = status;
        }

        @Override
        public TaskWorker.WorkerStatus status() {
            return status;
        }
    }

    private static final class HangingTaskQueue implements TaskQueue {

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
        public Uni<TaskQueue.QueueStats> stats() {
            return Uni.createFrom().nothing();
        }
    }

    private static final class HangingDeadLetterQueue implements TaskDeadLetterQueue {

        @Override
        public Uni<Void> publish(DeadLetterTask task) {
            return Uni.createFrom().voidItem();
        }

        @Override
        public Uni<Long> count() {
            return Uni.createFrom().nothing();
        }
    }

    private static final class RecordingTaskQueue implements TaskQueue {
        private final TaskQueue.QueueStats stats;
        private final RuntimeException failure;
        private int statsCalls;

        private RecordingTaskQueue(TaskQueue.QueueStats stats) {
            this.stats = stats;
            this.failure = null;
        }

        private RecordingTaskQueue(RuntimeException failure) {
            this.stats = null;
            this.failure = failure;
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
            if (failure != null) {
                return Uni.createFrom().failure(failure);
            }
            return Uni.createFrom().item(stats);
        }
    }

    private static final class RecordingDeadLetterQueue implements TaskDeadLetterQueue {
        private final long count;
        private final RuntimeException failure;
        private int countCalls;

        private RecordingDeadLetterQueue(long count) {
            this.count = count;
            this.failure = null;
        }

        private RecordingDeadLetterQueue(RuntimeException failure) {
            this.count = 0;
            this.failure = failure;
        }

        @Override
        public Uni<Void> publish(DeadLetterTask task) {
            return Uni.createFrom().voidItem();
        }

        @Override
        public Uni<Long> count() {
            countCalls++;
            if (failure != null) {
                return Uni.createFrom().failure(failure);
            }
            return Uni.createFrom().item(count);
        }
    }
}
