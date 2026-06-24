package tech.kayys.gamelan.dispatcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.smallrye.mutiny.subscription.Cancellable;
import tech.kayys.gamelan.engine.execution.ExecutionToken;
import tech.kayys.gamelan.engine.executor.ExecutorInfo;
import tech.kayys.gamelan.engine.node.NodeExecutionTask;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.protocol.CommunicationType;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;

class GrpcStreamTaskDispatcherTest {

    private InMemoryGrpcTaskStreamBroker broker;
    private GrpcStreamTaskDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        broker = new InMemoryGrpcTaskStreamBroker();
        dispatcher = new GrpcStreamTaskDispatcher();
        dispatcher.streamBroker = broker;
    }

    @Test
    void supportsGrpcExecutorsThatRequestStreamDelivery() {
        ExecutorInfo streamExecutor = executor("executor-1", "localhost:9000",
                Map.of(GrpcStreamTaskDispatcher.METADATA_GRPC_DELIVERY, "stream"));
        ExecutorInfo unaryExecutor = executor("executor-2", "localhost:9001", Map.of());
        ExecutorInfo pullExecutor = executor("executor-4", "", Map.of());
        ExecutorInfo restExecutor = new ExecutorInfo(
                "executor-3",
                "email",
                CommunicationType.REST,
                "http://localhost:8080",
                Duration.ofSeconds(10),
                Map.of(GrpcStreamTaskDispatcher.METADATA_GRPC_DELIVERY, "stream"));

        assertTrue(dispatcher.supports(streamExecutor));
        assertTrue(dispatcher.supports(pullExecutor));
        assertFalse(dispatcher.supports(unaryExecutor));
        assertFalse(dispatcher.supports(restExecutor));
    }

    @Test
    void dispatchAssignsTaskToExecutorStreamInbox() {
        NodeExecutionTask task = task();

        dispatcher.dispatch(task, executor("executor-1", "", Map.of())).await().indefinitely();

        List<GrpcTaskStreamBroker.StreamedTask> streamed = broker.stream("executor-1", 1)
                .select().first(1)
                .collect().asList()
                .await().atMost(Duration.ofSeconds(2));

        assertEquals(1, streamed.size());
        assertEquals("run-1:node-1:1", streamed.getFirst().taskId());
        assertEquals(task, streamed.getFirst().task());
    }

    @Test
    void inFlightTaskIsRequeuedWhenStreamSubscriberDisconnectsBeforeCompletion() {
        NodeExecutionTask task = task();
        dispatcher.dispatch(task, executor("executor-1", "", Map.of())).await().indefinitely();

        List<GrpcTaskStreamBroker.StreamedTask> firstDelivery = broker.stream("executor-1", 1)
                .select().first(1)
                .collect().asList()
                .await().atMost(Duration.ofSeconds(2));
        List<GrpcTaskStreamBroker.StreamedTask> secondDelivery = broker.stream("executor-1", 1)
                .select().first(1)
                .collect().asList()
                .await().atMost(Duration.ofSeconds(2));

        assertEquals(1, firstDelivery.size());
        assertEquals(1, secondDelivery.size());
        assertEquals(firstDelivery.getFirst().taskId(), secondDelivery.getFirst().taskId());
        assertEquals(task, secondDelivery.getFirst().task());
    }

    @Test
    void acknowledgedTaskIsNotRequeuedWhenStreamSubscriberDisconnectsBeforeCompletion() {
        NodeExecutionTask task = task();
        dispatcher.dispatch(task, executor("executor-1", "", Map.of())).await().indefinitely();

        List<GrpcTaskStreamBroker.StreamedTask> received = new CopyOnWriteArrayList<>();
        Cancellable subscription = broker.stream("executor-1", 1)
                .subscribe().with(received::add);

        assertEquals(1, received.size());
        broker.acknowledge(received.getFirst().taskId()).await().indefinitely();
        subscription.cancel();

        List<GrpcTaskStreamBroker.StreamedTask> redelivery = broker.stream("executor-1", 1)
                .ifNoItem().after(Duration.ofMillis(100)).recoverWithCompletion()
                .collect().asList()
                .await().atMost(Duration.ofSeconds(1));

        assertTrue(redelivery.isEmpty());

        broker.complete(received.getFirst().taskId()).await().indefinitely();
    }

    @Test
    void acknowledgedTaskIsRequeuedAfterAckLeaseExpiresBeforeCompletion() {
        AtomicLong clock = new AtomicLong(1_000L);
        broker.clockMillis = clock::get;
        broker.ackTimeout = Duration.ofMillis(100);
        NodeExecutionTask task = task();
        dispatcher.dispatch(task, executor("executor-1", "", Map.of())).await().indefinitely();

        List<GrpcTaskStreamBroker.StreamedTask> received = new CopyOnWriteArrayList<>();
        Cancellable subscription = broker.stream("executor-1", 1)
                .subscribe().with(received::add);

        assertEquals(1, received.size());
        broker.acknowledge(received.getFirst().taskId()).await().indefinitely();
        subscription.cancel();
        clock.addAndGet(101);

        List<GrpcTaskStreamBroker.StreamedTask> redelivery = broker.stream("executor-1", 1)
                .select().first(1)
                .collect().asList()
                .await().atMost(Duration.ofSeconds(2));

        assertEquals(1, redelivery.size());
        assertEquals(received.getFirst().taskId(), redelivery.getFirst().taskId());
        assertEquals(task, redelivery.getFirst().task());
    }

    @Test
    void completingAcknowledgedTaskBeforeLeaseExpiryPreventsLaterRedelivery() {
        AtomicLong clock = new AtomicLong(1_000L);
        broker.clockMillis = clock::get;
        broker.ackTimeout = Duration.ofMillis(100);
        NodeExecutionTask task = task();
        dispatcher.dispatch(task, executor("executor-1", "", Map.of())).await().indefinitely();

        List<GrpcTaskStreamBroker.StreamedTask> received = new CopyOnWriteArrayList<>();
        Cancellable subscription = broker.stream("executor-1", 1)
                .subscribe().with(received::add);

        assertEquals(1, received.size());
        String taskId = received.getFirst().taskId();
        broker.acknowledge(taskId).await().indefinitely();
        subscription.cancel();
        broker.complete(taskId).await().indefinitely();
        clock.addAndGet(101);

        List<GrpcTaskStreamBroker.StreamedTask> redelivery = broker.stream("executor-1", 1)
                .ifNoItem().after(Duration.ofMillis(100)).recoverWithCompletion()
                .collect().asList()
                .await().atMost(Duration.ofSeconds(1));

        assertTrue(redelivery.isEmpty());
    }

    @Test
    void duplicateTaskAssignmentIsIgnoredAcrossExecutors() {
        NodeExecutionTask task = task();

        broker.assign("executor-1", task).await().indefinitely();
        broker.assign("executor-2", task).await().indefinitely();

        List<GrpcTaskStreamBroker.StreamedTask> firstExecutor = broker.stream("executor-1", 1)
                .select().first(1)
                .collect().asList()
                .await().atMost(Duration.ofSeconds(2));
        List<GrpcTaskStreamBroker.StreamedTask> secondExecutor = broker.stream("executor-2", 1)
                .ifNoItem().after(Duration.ofMillis(100)).recoverWithCompletion()
                .collect().asList()
                .await().atMost(Duration.ofSeconds(1));

        assertEquals(1, firstExecutor.size());
        assertEquals("run-1:node-1:1", firstExecutor.getFirst().taskId());
        assertTrue(secondExecutor.isEmpty());
    }

    private ExecutorInfo executor(String id, String endpoint, Map<String, String> metadata) {
        return new ExecutorInfo(
                id,
                "email",
                CommunicationType.GRPC,
                endpoint,
                Duration.ofSeconds(10),
                metadata);
    }

    private NodeExecutionTask task() {
        return new NodeExecutionTask(
                WorkflowRunId.of("run-1"),
                NodeId.of("node-1"),
                1,
                new ExecutionToken(
                        "token-1",
                        WorkflowRunId.of("run-1"),
                        NodeId.of("node-1"),
                        1,
                        Instant.now().plusSeconds(60)),
                Map.of(),
                null);
    }
}
