package tech.kayys.gamelan.sdk.executor.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.engine.execution.ExecutionToken;
import tech.kayys.gamelan.engine.node.NodeExecutionResult;
import tech.kayys.gamelan.engine.node.NodeExecutionTask;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.protocol.CommunicationType;
import tech.kayys.gamelan.engine.run.RetryPolicy;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;

class BaseExecutorRuntimeTest {

    @Test
    void sendResultEnrichesResultWithTenantFromTaskContext() {
        RecordingRuntime runtime = new RecordingRuntime();
        NodeExecutionTask task = task(Map.of(NodeExecutionTask.TENANT_ID_KEY, "tenant-a"));
        NodeExecutionResult result = SimpleNodeExecutionResult.success(
                task.runId(),
                task.nodeId(),
                task.attempt(),
                Map.of("ok", true),
                task.token(),
                Duration.ZERO);

        runtime.send(task, result);

        assertEquals("tenant-a", runtime.recordingTransport.lastResult.getMetadata().get(NodeExecutionTask.TENANT_ID_KEY));
        assertEquals("tenant-a", runtime.recordingTransport.lastResult.getMetadata().get("tenantId"));
    }

    @Test
    void selectExecutorPrefersExactExecutorTypeFromTaskContext() {
        RecordingRuntime runtime = new RecordingRuntime();
        TestExecutor genericExecutor = new TestExecutor("generic", true);
        TestExecutor agentExecutor = new TestExecutor("agent.planner", true);
        runtime.registerExecutor(genericExecutor);
        runtime.registerExecutor(agentExecutor);

        WorkflowExecutor selected = runtime.select(task(Map.of(NodeExecutionTask.NODE_TYPE_KEY, "agent.planner")));

        assertSame(agentExecutor, selected);
    }

    @Test
    void selectExecutorFallsBackToReadyCompatibleExecutor() {
        RecordingRuntime runtime = new RecordingRuntime();
        TestExecutor unavailableExecutor = new TestExecutor("unavailable", false);
        TestExecutor readyExecutor = new TestExecutor("ready", true);
        runtime.registerExecutor(unavailableExecutor);
        runtime.registerExecutor(readyExecutor);

        WorkflowExecutor selected = runtime.select(task(Map.of(NodeExecutionTask.NODE_TYPE_KEY, "missing")));

        assertSame(readyExecutor, selected);
    }

    private static NodeExecutionTask task(Map<String, Object> context) {
        WorkflowRunId runId = WorkflowRunId.of("run-1");
        NodeId nodeId = NodeId.of("node-1");
        return new NodeExecutionTask(
                runId,
                nodeId,
                1,
                new ExecutionToken("token-1", runId, nodeId, 1, java.time.Instant.now().plusSeconds(60)),
                context,
                RetryPolicy.none());
    }

    private static final class RecordingRuntime extends BaseExecutorRuntime {
        private final RecordingTransport recordingTransport = new RecordingTransport();

        private RecordingRuntime() {
            this.transport = recordingTransport;
        }

        @Override
        protected ExecutorTransport createTransport() {
            return recordingTransport;
        }

        private void send(NodeExecutionTask task, NodeExecutionResult result) {
            sendResult(task, result);
        }

        private WorkflowExecutor select(NodeExecutionTask task) {
            return selectExecutor(task);
        }
    }

    private static final class RecordingTransport implements ExecutorTransport {
        private NodeExecutionResult lastResult;

        @Override
        public CommunicationType getCommunicationType() {
            return CommunicationType.LOCAL;
        }

        @Override
        public Multi<NodeExecutionTask> receiveTasks() {
            return Multi.createFrom().empty();
        }

        @Override
        public Uni<Void> sendResult(NodeExecutionResult result) {
            lastResult = result;
            return Uni.createFrom().voidItem();
        }
    }

    private record TestExecutor(String executorType, boolean ready) implements WorkflowExecutor {
        @Override
        public Uni<NodeExecutionResult> execute(NodeExecutionTask task) {
            return Uni.createFrom().failure(new UnsupportedOperationException("not used"));
        }

        @Override
        public String getExecutorType() {
            return executorType;
        }

        @Override
        public boolean isReady() {
            return ready;
        }
    }
}
