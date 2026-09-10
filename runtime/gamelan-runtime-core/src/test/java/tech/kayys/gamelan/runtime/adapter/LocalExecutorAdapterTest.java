package tech.kayys.gamelan.runtime.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

import org.junit.jupiter.api.Test;

import tech.kayys.gamelan.engine.error.ErrorCode;
import tech.kayys.gamelan.engine.error.GamelanException;
import tech.kayys.gamelan.engine.executor.ExecutorClient;
import tech.kayys.gamelan.engine.node.NodeContext;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.node.NodeResult;

class LocalExecutorAdapterTest {

    @Test
    void adaptRegistersClientAndExecuteRoutesByNodeType() {
        LocalExecutorAdapter adapter = new LocalExecutorAdapter();
        RecordingClient client = new RecordingClient("agent.skill");

        assertSame(client, adapter.adapt(client));
        assertTrue(adapter.hasClient("agent.skill"));

        NodeResult result = adapter.execute(
                node("agent.skill"),
                Map.of("prompt", "hello"))
                .toCompletableFuture()
                .join();

        assertTrue(result.success());
        assertEquals(Map.of("handledBy", "agent.skill"), result.output());
        assertEquals("node-1", client.nodeContext.nodeId().value());
        assertEquals("hello", client.variables.get("prompt"));
    }

    @Test
    void executeDefaultsNullVariablesToEmptyMap() {
        LocalExecutorAdapter adapter = new LocalExecutorAdapter();
        RecordingClient client = new RecordingClient("agent.skill");
        adapter.adapt(client);

        adapter.execute(node("agent.skill"), null).toCompletableFuture().join();

        assertTrue(client.variables.isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> client.variables.put("x", "y"));
    }

    @Test
    void unregisterRemovesRegisteredClient() {
        LocalExecutorAdapter adapter = new LocalExecutorAdapter();
        adapter.adapt(new RecordingClient("agent.skill"));

        adapter.unregister("agent.skill");

        assertFalse(adapter.hasClient("agent.skill"));
        assertFailure(
                ErrorCode.TASK_EXECUTOR_UNAVAILABLE,
                () -> adapter.execute(node("agent.skill"), Map.of()).toCompletableFuture().join());
    }

    @Test
    void executeFailsWhenNoClientRegisteredForNodeType() {
        LocalExecutorAdapter adapter = new LocalExecutorAdapter();

        GamelanException exception = assertFailure(
                ErrorCode.TASK_EXECUTOR_UNAVAILABLE,
                () -> adapter.execute(node("agent.skill"), Map.of()).toCompletableFuture().join());

        assertTrue(exception.getSafeMessage().contains("agent.skill"));
    }

    @Test
    void executeRejectsNullOrBlankNodeContext() {
        LocalExecutorAdapter adapter = new LocalExecutorAdapter();

        assertFailure(
                ErrorCode.TASK_VALIDATION_FAILED,
                () -> adapter.execute(null, Map.of()).toCompletableFuture().join());
        GamelanException exception = assertThrows(GamelanException.class, () -> node(" "));
        assertEquals(ErrorCode.VALIDATION_FAILED, exception.getErrorCode());
    }

    @Test
    void executeFailsWhenClientReturnsNullStageOrNullResult() {
        LocalExecutorAdapter nullStageAdapter = new LocalExecutorAdapter();
        nullStageAdapter.adapt(new NullStageClient("agent.skill"));

        LocalExecutorAdapter nullResultAdapter = new LocalExecutorAdapter();
        nullResultAdapter.adapt(new NullResultClient("agent.skill"));

        assertFailure(
                ErrorCode.RUNTIME_ERROR,
                () -> nullStageAdapter.execute(node("agent.skill"), Map.of()).toCompletableFuture().join());
        assertFailure(
                ErrorCode.RUNTIME_ERROR,
                () -> nullResultAdapter.execute(node("agent.skill"), Map.of()).toCompletableFuture().join());
    }

    private static GamelanException assertFailure(ErrorCode errorCode, Runnable operation) {
        CompletionException exception = assertThrows(CompletionException.class, operation::run);
        GamelanException cause = (GamelanException) exception.getCause();
        assertEquals(errorCode, cause.getErrorCode());
        return cause;
    }

    private static NodeContext node(String nodeType) {
        return new NodeContext(NodeId.of("node-1"), nodeType, Map.of("input", "value"), Map.of());
    }

    private static final class RecordingClient implements ExecutorClient {
        private final String executorType;
        private NodeContext nodeContext;
        private Map<String, Object> variables;

        private RecordingClient(String executorType) {
            this.executorType = executorType;
        }

        @Override
        public String executorType() {
            return executorType;
        }

        @Override
        public CompletionStage<NodeResult> execute(NodeContext node, Map<String, Object> workflowVariables) {
            nodeContext = node;
            variables = workflowVariables;
            return CompletableFuture.completedFuture(NodeResult.success(Map.of("handledBy", executorType)));
        }
    }

    private static final class NullStageClient implements ExecutorClient {
        private final String executorType;

        private NullStageClient(String executorType) {
            this.executorType = executorType;
        }

        @Override
        public String executorType() {
            return executorType;
        }

        @Override
        public CompletionStage<NodeResult> execute(NodeContext node, Map<String, Object> workflowVariables) {
            return null;
        }
    }

    private static final class NullResultClient implements ExecutorClient {
        private final String executorType;

        private NullResultClient(String executorType) {
            this.executorType = executorType;
        }

        @Override
        public String executorType() {
            return executorType;
        }

        @Override
        public CompletionStage<NodeResult> execute(NodeContext node, Map<String, Object> workflowVariables) {
            return CompletableFuture.completedFuture(null);
        }
    }
}
