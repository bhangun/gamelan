package tech.kayys.gamelan.runtime.orchestration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import tech.kayys.gamelan.engine.executor.ExecutorClient;
import tech.kayys.gamelan.engine.node.NodeContext;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.node.NodeResult;
import tech.kayys.gamelan.runtime.ExecutorAdapter;
import tech.kayys.gamelan.runtime.ExecutorAdapterRegistry;

class TaskExecutionCoordinatorTest {

    @Test
    void executeWithRetry_reinvokesAdapterOnEachRetryAttempt() {
        RetryingAdapter adapter = new RetryingAdapter(2);
        TaskExecutionCoordinator coordinator = coordinator(adapter);

        NodeResult result = coordinator.executeWithRetry("local", node(), Map.of("input", "value"), 2)
                .toCompletableFuture()
                .join();

        assertTrue(result.success());
        assertEquals(3, result.output());
        assertEquals(3, adapter.attempts());
    }

    @Test
    void executeWithRetry_treatsNegativeRetryCountAsNoRetry() {
        RetryingAdapter adapter = new RetryingAdapter(1);
        TaskExecutionCoordinator coordinator = coordinator(adapter);

        CompletionException error = assertThrows(CompletionException.class,
                () -> coordinator.executeWithRetry("local", node(), Map.of(), -1)
                        .toCompletableFuture()
                        .join());

        assertInstanceOf(IllegalStateException.class, error.getCause());
        assertEquals(1, adapter.attempts());
    }

    private static TaskExecutionCoordinator coordinator(ExecutorAdapter adapter) {
        TaskExecutionCoordinator coordinator = new TaskExecutionCoordinator();
        coordinator.adapterRegistry = new ExecutorAdapterRegistry(Stream.of(adapter));
        return coordinator;
    }

    private static NodeContext node() {
        return new NodeContext(NodeId.of("node-1"), "task", Map.of(), Map.of());
    }

    private static final class RetryingAdapter implements ExecutorAdapter {
        private final int failuresBeforeSuccess;
        private final AtomicInteger attempts = new AtomicInteger();

        private RetryingAdapter(int failuresBeforeSuccess) {
            this.failuresBeforeSuccess = failuresBeforeSuccess;
        }

        @Override
        public boolean supports(String executorType) {
            return "local".equals(executorType);
        }

        @Override
        public ExecutorClient adapt(ExecutorClient client) {
            return client;
        }

        @Override
        public CompletionStage<NodeResult> execute(NodeContext nodeContext, Map<String, Object> variables) {
            int attempt = attempts.incrementAndGet();
            if (attempt <= failuresBeforeSuccess) {
                return CompletableFuture.failedFuture(new IllegalStateException("boom-" + attempt));
            }
            return CompletableFuture.completedFuture(NodeResult.success(attempt));
        }

        @Override
        public String getExecutorType() {
            return "local";
        }

        private int attempts() {
            return attempts.get();
        }
    }
}
