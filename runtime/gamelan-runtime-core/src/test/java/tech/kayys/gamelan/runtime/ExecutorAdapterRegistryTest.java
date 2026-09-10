package tech.kayys.gamelan.runtime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.Stream;

import tech.kayys.gamelan.engine.executor.ExecutorClient;
import tech.kayys.gamelan.engine.node.NodeContext;
import tech.kayys.gamelan.engine.node.NodeResult;

class ExecutorAdapterRegistryTest {

    private ExecutorAdapter adapter;
    private ExecutorAdapterRegistry registry;

    @BeforeEach
    void setUp() {
        adapter = new TestExecutorAdapter("test");
        registry = new ExecutorAdapterRegistry(Stream.of(adapter));
    }

    @Test
    void testRegisterAdapter() {
        assertTrue(registry.hasAdapter("test"));
        assertEquals(adapter, registry.getAdapter("test"));
    }

    @Test
    void testGetAdapterThrowsWhenNotFound() {
        assertThrows(IllegalArgumentException.class, () -> {
            registry.getAdapter("nonexistent");
        });
    }

    @Test
    void testGetAllAdapters() {
        assertEquals(1, registry.getAllAdapters().size());
        assertTrue(registry.getAllAdapters().containsKey("test"));
    }

    private record TestExecutorAdapter(String executorType) implements ExecutorAdapter {

        @Override
        public boolean supports(String executorType) {
            return this.executorType.equals(executorType);
        }

        @Override
        public ExecutorClient adapt(ExecutorClient client) {
            return client;
        }

        @Override
        public CompletionStage<NodeResult> execute(NodeContext nodeContext, Map<String, Object> variables) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public String getExecutorType() {
            return executorType;
        }
    }
}
