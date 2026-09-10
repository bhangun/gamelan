package tech.kayys.gamelan.sdk.executor.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.engine.node.NodeExecutionResult;
import tech.kayys.gamelan.engine.node.NodeExecutionStatus;
import tech.kayys.gamelan.engine.node.NodeExecutionTask;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.run.RetryPolicy;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;

class AbstractWorkflowExecutorTest {

    @Test
    void executeWithLifecycle_recoversFailuresWithoutDoubleReleasingCapacity() {
        FailingExecutor executor = new FailingExecutor();

        NodeExecutionResult result = executor.executeWithLifecycle(task()).await().indefinitely();

        assertEquals(NodeExecutionStatus.FAILED, result.status());
        assertEquals(0, executor.getActiveTaskCount());
        assertEquals(1L, executor.getMetrics().getMetrics().get("tasksStarted"));
        assertEquals(0L, executor.getMetrics().getMetrics().get("tasksCompleted"));
        assertEquals(1L, executor.getMetrics().getMetrics().get("tasksFailed"));
    }

    @Test
    void executeWithLifecycle_rejectsTaskWhenConcurrencyLimitIsReached() {
        LimitedExecutor executor = new LimitedExecutor();
        executor.reserveCapacityForTest();

        NodeExecutionResult result = executor.executeWithLifecycle(task()).await().indefinitely();

        assertEquals(NodeExecutionStatus.FAILED, result.status());
        assertTrue(result.error().message().contains("Executor not ready"));
        assertEquals(1, executor.getActiveTaskCount());
        assertEquals(0L, executor.getMetrics().getMetrics().get("tasksStarted"));
        assertEquals(0L, executor.getMetrics().getMetrics().get("tasksFailed"));
    }

    private static NodeExecutionTask task() {
        return new NodeExecutionTask(
                WorkflowRunId.of("run-1"),
                NodeId.of("node-1"),
                1,
                null,
                Map.of("__node_type__", "task"),
                RetryPolicy.none());
    }

    @Executor(executorType = "failing", maxConcurrentTasks = 1)
    private static final class FailingExecutor extends AbstractWorkflowExecutor {

        @Override
        public Uni<NodeExecutionResult> execute(NodeExecutionTask task) {
            return Uni.createFrom().failure(new IllegalStateException("boom"));
        }
    }

    @Executor(executorType = "limited", maxConcurrentTasks = 1)
    private static final class LimitedExecutor extends AbstractWorkflowExecutor {

        @Override
        public Uni<NodeExecutionResult> execute(NodeExecutionTask task) {
            return Uni.createFrom().item(SimpleNodeExecutionResult.success(
                    task.runId(),
                    task.nodeId(),
                    task.attempt(),
                    Map.of("ok", true),
                    task.token(),
                    Duration.ZERO));
        }

        void reserveCapacityForTest() {
            activeTaskCount.incrementAndGet();
        }
    }
}
