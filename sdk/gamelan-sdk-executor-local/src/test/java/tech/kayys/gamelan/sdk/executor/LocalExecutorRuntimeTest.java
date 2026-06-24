package tech.kayys.gamelan.sdk.executor;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tech.kayys.gamelan.engine.node.NodeExecutionResult;
import tech.kayys.gamelan.engine.node.NodeExecutionTask;
import tech.kayys.gamelan.engine.protocol.CommunicationType;
import tech.kayys.gamelan.sdk.executor.core.ExecutorTransport;
import tech.kayys.gamelan.sdk.executor.core.WorkflowExecutor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalExecutorRuntimeTest {

    private TestLocalExecutorRuntime runtime;

    @AfterEach
    void tearDown() {
        if (runtime != null && runtime.isRunning()) {
            runtime.stop();
        }
    }

    @Test
    void startRegistersExecutorsAndSubscribesToLocalTransport() {
        RecordingTransport transport = new RecordingTransport();
        runtime = new TestLocalExecutorRuntime(transport);
        runtime.registerExecutor(new TestWorkflowExecutor("test-executor"));

        runtime.start();

        assertTrue(runtime.isRunning());
        assertTrue(transport.receiveTasksCalled);
        assertEquals(1, transport.registeredExecutors.size());
        assertEquals("test-executor", transport.registeredExecutors.getFirst().getExecutorType());
    }

    @Test
    void stopUnregistersLocalTransport() {
        RecordingTransport transport = new RecordingTransport();
        runtime = new TestLocalExecutorRuntime(transport);
        runtime.registerExecutor(new TestWorkflowExecutor("test-executor"));

        runtime.start();
        runtime.stop();

        assertTrue(transport.unregistered);
    }

    private static final class TestLocalExecutorRuntime extends LocalExecutorRuntime {
        private final RecordingTransport transport;

        private TestLocalExecutorRuntime(RecordingTransport transport) {
            this.transport = transport;
        }

        @Override
        protected ExecutorTransport createTransport() {
            return transport;
        }
    }

    private static final class RecordingTransport implements ExecutorTransport {
        private boolean receiveTasksCalled;
        private boolean unregistered;
        private List<WorkflowExecutor> registeredExecutors = List.of();

        @Override
        public CommunicationType getCommunicationType() {
            return CommunicationType.LOCAL;
        }

        @Override
        public Multi<NodeExecutionTask> receiveTasks() {
            receiveTasksCalled = true;
            return Multi.createFrom().empty();
        }

        @Override
        public Uni<Void> sendResult(NodeExecutionResult result) {
            return Uni.createFrom().voidItem();
        }

        @Override
        public Uni<Void> register(List<WorkflowExecutor> executors) {
            registeredExecutors = List.copyOf(executors);
            return Uni.createFrom().voidItem();
        }

        @Override
        public Uni<Void> unregister() {
            unregistered = true;
            return Uni.createFrom().voidItem();
        }
    }

    private record TestWorkflowExecutor(String executorType) implements WorkflowExecutor {
        @Override
        public Uni<NodeExecutionResult> execute(NodeExecutionTask task) {
            return Uni.createFrom().nullItem();
        }

        @Override
        public String getExecutorType() {
            return executorType;
        }
    }
}
