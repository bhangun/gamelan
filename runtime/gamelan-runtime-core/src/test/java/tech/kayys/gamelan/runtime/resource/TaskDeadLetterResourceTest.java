package tech.kayys.gamelan.runtime.resource;

import static tech.kayys.gamelan.engine.executor.ExecutorSelectionRejectionReasons.CAPACITY_SATURATED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import tech.kayys.gamelan.engine.node.NodeExecutionTask;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.run.RetryPolicy;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;
import tech.kayys.gamelan.scheduler.TaskDeadLetterQueue;
import tech.kayys.gamelan.scheduler.TaskQueue;
import tech.kayys.gamelan.scheduler.TaskQueueMetadata;

class TaskDeadLetterResourceTest {

    @Test
    void requeueEnqueuesCleanTaskThenDeletesDeadLetter() {
        RecordingDeadLetterQueue deadLetters = new RecordingDeadLetterQueue(deadLetter("message-1"));
        RecordingTaskQueue taskQueue = new RecordingTaskQueue();
        TaskDeadLetterResource resource = resource(deadLetters, taskQueue);

        TaskDeadLetterQueue.DeadLetterTask replayed = resource.requeue("message-1").await().indefinitely();

        assertEquals("message-1", replayed.messageId());
        assertEquals(1, taskQueue.enqueueCount);
        assertEquals("message-1", deadLetters.deletedMessageId);
        assertEquals(1, deadLetters.deleteCount);
        assertFalse(taskQueue.enqueuedTask.context().containsKey(TaskQueueMetadata.DEFER_COUNT_KEY));
        assertFalse(taskQueue.enqueuedTask.context().containsKey(TaskQueueMetadata.DELIVERY_ATTEMPT_KEY));
        assertFalse(taskQueue.enqueuedTask.context().containsKey(TaskQueueMetadata.FIRST_SEEN_AT_KEY));
        assertFalse(taskQueue.enqueuedTask.context().containsKey(TaskQueueMetadata.LAST_DEFER_REASON_KEY));
    }

    @Test
    void requeueReturnsNotFoundWhenDeadLetterDoesNotExist() {
        TaskDeadLetterResource resource = resource(new RecordingDeadLetterQueue(null), new RecordingTaskQueue());

        assertThrows(NotFoundException.class, () -> resource.requeue("missing").await().indefinitely());
    }

    @Test
    void requeueDoesNotDeleteDeadLetterWhenEnqueueFails() {
        RecordingDeadLetterQueue deadLetters = new RecordingDeadLetterQueue(deadLetter("message-1"));
        RecordingTaskQueue taskQueue = new RecordingTaskQueue();
        taskQueue.enqueueFailure = new IllegalStateException("queue offline");
        TaskDeadLetterResource resource = resource(deadLetters, taskQueue);

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> resource.requeue("message-1").await().indefinitely());

        assertSame(taskQueue.enqueueFailure, thrown);
        assertEquals(1, taskQueue.enqueueCount);
        assertEquals(0, deadLetters.deleteCount);
    }

    @Test
    void requeueFailsWhenDeadLetterCleanupIsNotConfirmed() {
        RecordingDeadLetterQueue deadLetters = new RecordingDeadLetterQueue(deadLetter("message-1"));
        deadLetters.deleteResult = false;
        RecordingTaskQueue taskQueue = new RecordingTaskQueue();
        TaskDeadLetterResource resource = resource(deadLetters, taskQueue);

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> resource.requeue("message-1").await().indefinitely());

        assertEquals("Task dead letter was requeued but cleanup was not confirmed: message-1", thrown.getMessage());
        assertEquals(1, taskQueue.enqueueCount);
        assertEquals(1, deadLetters.deleteCount);
    }

    @Test
    void clearWithoutFiltersClearsAllDeadLetters() {
        RecordingDeadLetterQueue deadLetters = new RecordingDeadLetterQueue(deadLetter("message-1"));
        TaskDeadLetterResource resource = resource(deadLetters, new RecordingTaskQueue());

        resource.clear(null, null, null, null).await().indefinitely();

        assertEquals(1, deadLetters.clearCount);
        assertEquals(0, deadLetters.filteredClearCount);
    }

    @Test
    void clearWithFiltersClearsOnlyMatchingDeadLetters() {
        RecordingDeadLetterQueue deadLetters = new RecordingDeadLetterQueue(deadLetter("message-1"));
        TaskDeadLetterResource resource = resource(deadLetters, new RecordingTaskQueue());

        resource.clear("run-1", null, "tenant-a", CAPACITY_SATURATED).await().indefinitely();

        assertEquals(0, deadLetters.clearCount);
        assertEquals(1, deadLetters.filteredClearCount);
        assertEquals("run-1", deadLetters.filteredClearQuery.runId());
        assertEquals("tenant-a", deadLetters.filteredClearQuery.tenantId());
        assertEquals(CAPACITY_SATURATED, deadLetters.filteredClearQuery.reason());
    }

    @Test
    void deleteRemovesSingleDeadLetterByMessageId() {
        RecordingDeadLetterQueue deadLetters = new RecordingDeadLetterQueue(deadLetter("message-1"));
        TaskDeadLetterResource resource = resource(deadLetters, new RecordingTaskQueue());

        resource.delete("message-1").await().indefinitely();

        assertEquals("message-1", deadLetters.deletedMessageId);
        assertEquals(1, deadLetters.deleteCount);
    }

    @Test
    void deleteReturnsNotFoundWhenDeadLetterDoesNotExist() {
        RecordingDeadLetterQueue deadLetters = new RecordingDeadLetterQueue(deadLetter("message-1"));
        deadLetters.deleteResult = false;
        TaskDeadLetterResource resource = resource(deadLetters, new RecordingTaskQueue());

        assertThrows(NotFoundException.class, () -> resource.delete("missing").await().indefinitely());
    }

    @Test
    void bulkRequeueRequiresFilterOrExplicitAllFlag() {
        TaskDeadLetterResource resource = resource(
                new RecordingDeadLetterQueue(deadLetter("message-1")),
                new RecordingTaskQueue());

        assertThrows(BadRequestException.class,
                () -> resource.requeueMatching(null, null, null, null, null, false).await().indefinitely());
    }

    @Test
    void bulkRequeueEnqueuesCleanTasksAndDeletesDeadLetters() {
        RecordingDeadLetterQueue deadLetters = new RecordingDeadLetterQueue(deadLetter("message-1"));
        deadLetters.listEntries = List.of(deadLetter("message-1"), deadLetter("message-2"));
        RecordingTaskQueue taskQueue = new RecordingTaskQueue();
        TaskDeadLetterResource resource = resource(deadLetters, taskQueue);

        TaskDeadLetterResource.DeadLetterRequeueResponse response = resource
                .requeueMatching(10, "run-1", null, "tenant-a", CAPACITY_SATURATED, false)
                .await()
                .indefinitely();

        assertEquals(2, response.selected());
        assertEquals(2, response.requeued());
        assertEquals(0, response.failed());
        assertEquals(0, response.skipped());
        assertEquals(List.of("message-1", "message-2"), response.requeuedMessageIds());
        assertEquals(2, taskQueue.enqueueCount);
        assertFalse(taskQueue.enqueuedTasks.getFirst().context().containsKey(TaskQueueMetadata.DEFER_COUNT_KEY));
        assertEquals(2, deadLetters.deleteCount);
        assertEquals("run-1", deadLetters.listQuery.runId());
        assertEquals("tenant-a", deadLetters.listQuery.tenantId());
        assertEquals(CAPACITY_SATURATED, deadLetters.listQuery.reason());
    }

    @Test
    void bulkRequeueStopsAfterFirstFailureAndReportsSkippedEntries() {
        RecordingDeadLetterQueue deadLetters = new RecordingDeadLetterQueue(deadLetter("message-1"));
        deadLetters.listEntries = List.of(deadLetter("message-1"), deadLetter("message-2"));
        RecordingTaskQueue taskQueue = new RecordingTaskQueue();
        taskQueue.enqueueFailure = new IllegalStateException("queue offline");
        TaskDeadLetterResource resource = resource(deadLetters, taskQueue);

        TaskDeadLetterResource.DeadLetterRequeueResponse response = resource
                .requeueMatching(10, "run-1", null, null, null, false)
                .await()
                .indefinitely();

        assertEquals(2, response.selected());
        assertEquals(0, response.requeued());
        assertEquals(1, response.failed());
        assertEquals(1, response.skipped());
        assertEquals("message-1", response.failures().getFirst().messageId());
        assertEquals("queue offline", response.failures().getFirst().error());
        assertEquals(1, taskQueue.enqueueCount);
        assertEquals(0, deadLetters.deleteCount);
    }

    private static TaskDeadLetterResource resource(
            RecordingDeadLetterQueue deadLetters,
            RecordingTaskQueue taskQueue) {
        TaskDeadLetterResource resource = new TaskDeadLetterResource();
        resource.deadLetterQueue = deadLetters;
        resource.taskQueue = taskQueue;
        return resource;
    }

    private static TaskDeadLetterQueue.DeadLetterTask deadLetter(String messageId) {
        return new TaskDeadLetterQueue.DeadLetterTask(
                messageId,
                task(),
                CAPACITY_SATURATED,
                3,
                2,
                Instant.now().minusSeconds(30),
                Instant.now(),
                Map.of());
    }

    private static NodeExecutionTask task() {
        Map<String, Object> context = new HashMap<>();
        context.put(NodeExecutionTask.NODE_TYPE_KEY, "agent");
        context.put(TaskQueueMetadata.DEFER_COUNT_KEY, 2);
        context.put(TaskQueueMetadata.DELIVERY_ATTEMPT_KEY, 3);
        context.put(TaskQueueMetadata.FIRST_SEEN_AT_KEY, Instant.now().minusSeconds(30).toString());
        context.put(TaskQueueMetadata.LAST_DEFER_REASON_KEY, CAPACITY_SATURATED);
        return new NodeExecutionTask(
                WorkflowRunId.of("run-1"),
                NodeId.of("node-1"),
                1,
                null,
                context,
                RetryPolicy.none());
    }

    private static final class RecordingDeadLetterQueue implements TaskDeadLetterQueue {
        private final DeadLetterTask deadLetter;
        private List<DeadLetterTask> listEntries = List.of();
        private int deleteCount;
        private int clearCount;
        private int filteredClearCount;
        private boolean deleteResult = true;
        private String deletedMessageId;
        private DeadLetterQuery listQuery;
        private DeadLetterQuery filteredClearQuery;

        private RecordingDeadLetterQueue(DeadLetterTask deadLetter) {
            this.deadLetter = deadLetter;
        }

        @Override
        public Uni<Void> publish(DeadLetterTask task) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Uni<Optional<DeadLetterTask>> get(String messageId) {
            return Uni.createFrom().item(deadLetter != null && deadLetter.messageId().equals(messageId)
                    ? Optional.of(deadLetter)
                    : Optional.empty());
        }

        @Override
        public Uni<List<DeadLetterTask>> list(DeadLetterQuery query) {
            listQuery = query;
            return Uni.createFrom().item(listEntries);
        }

        @Override
        public Uni<Boolean> delete(String messageId) {
            deleteCount++;
            deletedMessageId = messageId;
            return Uni.createFrom().item(deleteResult);
        }

        @Override
        public Uni<Long> clear(DeadLetterQuery query) {
            filteredClearCount++;
            filteredClearQuery = query;
            return Uni.createFrom().item(1L);
        }

        @Override
        public Uni<Void> clear() {
            clearCount++;
            return Uni.createFrom().voidItem();
        }
    }

    private static final class RecordingTaskQueue implements TaskQueue {
        private int enqueueCount;
        private NodeExecutionTask enqueuedTask;
        private final List<NodeExecutionTask> enqueuedTasks = new ArrayList<>();
        private RuntimeException enqueueFailure;

        @Override
        public Uni<Void> enqueue(NodeExecutionTask task) {
            enqueueCount++;
            if (enqueueFailure != null) {
                return Uni.createFrom().failure(enqueueFailure);
            }
            enqueuedTask = task;
            enqueuedTasks.add(task);
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
    }
}
