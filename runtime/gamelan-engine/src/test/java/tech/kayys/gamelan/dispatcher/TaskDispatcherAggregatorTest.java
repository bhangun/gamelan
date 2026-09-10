package tech.kayys.gamelan.dispatcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.inject.Instance;
import tech.kayys.gamelan.engine.error.ErrorCode;
import tech.kayys.gamelan.engine.error.GamelanException;
import tech.kayys.gamelan.engine.execution.ExecutionToken;
import tech.kayys.gamelan.engine.executor.ExecutorInfo;
import tech.kayys.gamelan.engine.node.NodeExecutionTask;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.protocol.CommunicationType;
import tech.kayys.gamelan.engine.run.RetryPolicy;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;

class TaskDispatcherAggregatorTest {

    private static final WorkflowRunId RUN_ID = WorkflowRunId.of("run-1");
    private static final NodeId NODE_ID = NodeId.of("node-1");

    @Test
    void dispatch_selectsHighestPrioritySupportingDispatcher() {
        RecordingDispatcher lowPriority = new RecordingDispatcher(CommunicationType.LOCAL, 1);
        RecordingDispatcher highPriority = new RecordingDispatcher(CommunicationType.LOCAL, 10);
        TaskDispatcherAggregator aggregator = aggregator(lowPriority, highPriority);

        NodeExecutionTask task = task();
        ExecutorInfo executor = executor(CommunicationType.LOCAL);

        aggregator.dispatch(task, executor).await().indefinitely();

        assertEquals(0, lowPriority.dispatchCount);
        assertEquals(1, highPriority.dispatchCount);
        assertSame(task, highPriority.task);
        assertSame(executor, highPriority.executor);
    }

    @Test
    void dispatch_skipsDispatcherWhenSupportCheckFails() {
        RecordingDispatcher broken = new RecordingDispatcher(CommunicationType.LOCAL, 10);
        broken.supportsFailure = new IllegalStateException("plugin metadata failed");
        RecordingDispatcher fallback = new RecordingDispatcher(CommunicationType.LOCAL, 1);
        TaskDispatcherAggregator aggregator = aggregator(broken, fallback);

        aggregator.dispatch(task(), executor(CommunicationType.LOCAL)).await().indefinitely();

        assertEquals(0, broken.dispatchCount);
        assertEquals(1, fallback.dispatchCount);
    }

    @Test
    void registerDispatcher_invalidatesCachedDispatcherCatalog() {
        TaskDispatcherAggregator aggregator = aggregator();

        GamelanException firstFailure = assertDispatchFailure(
                () -> aggregator.dispatch(task(), executor(CommunicationType.REST)).await().indefinitely());

        assertEquals(ErrorCode.DISPATCHER_NOT_FOUND, firstFailure.getErrorCode());

        RecordingDispatcher pluginDispatcher = new RecordingDispatcher(CommunicationType.REST, 50);
        aggregator.registerDispatcher(pluginDispatcher);

        aggregator.dispatch(task(), executor(CommunicationType.REST)).await().indefinitely();

        assertEquals(1, pluginDispatcher.dispatchCount);
    }

    @Test
    void dispatch_whenNoDispatcherSupportsExecutor_failsWithDispatcherError() {
        TaskDispatcherAggregator aggregator = aggregator(new RecordingDispatcher(CommunicationType.LOCAL, 1));

        GamelanException exception = assertDispatchFailure(
                () -> aggregator.dispatch(task(), executor(CommunicationType.GRPC)).await().indefinitely());

        assertEquals(ErrorCode.DISPATCHER_NOT_FOUND, exception.getErrorCode());
        assertEquals(400, exception.getHttpStatusCode());
        assertTrue(exception.getSafeMessage().contains("executor-1"));
        assertTrue(exception.getSafeMessage().contains("GRPC"));
    }

    @Test
    void dispatch_rejectsInvalidInputsWithDispatcherInvalidRequest() {
        TaskDispatcherAggregator aggregator = aggregator(new RecordingDispatcher(CommunicationType.LOCAL, 1));

        GamelanException nullTask = assertDispatchFailure(
                () -> aggregator.dispatch(null, executor(CommunicationType.LOCAL)).await().indefinitely());
        GamelanException nullExecutor = assertDispatchFailure(
                () -> aggregator.dispatch(task(), null).await().indefinitely());

        assertEquals(ErrorCode.DISPATCHER_INVALID_REQUEST, nullTask.getErrorCode());
        assertEquals(ErrorCode.DISPATCHER_INVALID_REQUEST, nullExecutor.getErrorCode());
    }

    @Test
    void dispatch_whenSelectedDispatcherReturnsNullUni_failsWithBadResponse() {
        RecordingDispatcher dispatcher = new RecordingDispatcher(CommunicationType.LOCAL, 1);
        dispatcher.returnNullDispatch = true;
        TaskDispatcherAggregator aggregator = aggregator(dispatcher);

        GamelanException exception = assertDispatchFailure(
                () -> aggregator.dispatch(task(), executor(CommunicationType.LOCAL)).await().indefinitely());

        assertEquals(ErrorCode.DISPATCHER_BAD_RESPONSE, exception.getErrorCode());
        assertEquals(502, exception.getHttpStatusCode());
    }

    @Test
    void registerDispatcher_rejectsNullDispatcher() {
        TaskDispatcherAggregator aggregator = aggregator();

        GamelanException exception = assertThrows(GamelanException.class, () -> aggregator.registerDispatcher(null));

        assertEquals(ErrorCode.DISPATCHER_INVALID_REQUEST, exception.getErrorCode());
    }

    private static GamelanException assertDispatchFailure(Runnable operation) {
        return assertThrows(GamelanException.class, operation::run);
    }

    private static TaskDispatcherAggregator aggregator(TaskDispatcher... dispatchers) {
        TaskDispatcherAggregator aggregator = new TaskDispatcherAggregator();
        aggregator.availableDispatchers = instance(dispatchers);
        return aggregator;
    }

    @SuppressWarnings("unchecked")
    private static Instance<TaskDispatcher> instance(TaskDispatcher... dispatchers) {
        Instance<TaskDispatcher> instance = mock(Instance.class);
        when(instance.iterator()).thenAnswer(invocation -> List.of(dispatchers).iterator());
        return instance;
    }

    private static NodeExecutionTask task() {
        return new NodeExecutionTask(
                RUN_ID,
                NODE_ID,
                1,
                new ExecutionToken("token-1", RUN_ID, NODE_ID, 1, Instant.now().plusSeconds(60)),
                Map.of(),
                RetryPolicy.none());
    }

    private static ExecutorInfo executor(CommunicationType communicationType) {
        return new ExecutorInfo(
                "executor-1",
                "agent",
                communicationType,
                "localhost:9000",
                Duration.ofSeconds(10),
                Map.of());
    }

    private static final class RecordingDispatcher implements TaskDispatcher {
        private final CommunicationType supportedType;
        private final int priority;

        private RuntimeException supportsFailure;
        private boolean returnNullDispatch;
        private int dispatchCount;
        private NodeExecutionTask task;
        private ExecutorInfo executor;

        private RecordingDispatcher(CommunicationType supportedType, int priority) {
            this.supportedType = supportedType;
            this.priority = priority;
        }

        @Override
        public Uni<Void> dispatch(NodeExecutionTask task, ExecutorInfo executor) {
            dispatchCount++;
            this.task = task;
            this.executor = executor;
            if (returnNullDispatch) {
                return null;
            }
            return Uni.createFrom().voidItem();
        }

        @Override
        public boolean supports(ExecutorInfo executor) {
            if (supportsFailure != null) {
                throw supportsFailure;
            }
            return executor != null && executor.communicationType() == supportedType;
        }

        @Override
        public int getPriority() {
            return priority;
        }
    }
}
