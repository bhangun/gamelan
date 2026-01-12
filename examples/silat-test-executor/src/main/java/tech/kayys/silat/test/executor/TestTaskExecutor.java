package tech.kayys.silat.test.executor;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import tech.kayys.silat.sdk.executor.AbstractWorkflowExecutor;
import tech.kayys.silat.sdk.executor.SimpleNodeExecutionResult;
import tech.kayys.silat.execution.NodeExecutionTask;

import java.util.Map;

import tech.kayys.silat.sdk.executor.Executor;
import tech.kayys.silat.model.CommunicationType;

@ApplicationScoped
@Executor(executorType = "test-executor", maxConcurrentTasks = 10, supportedNodeTypes = {
        "simple-test-task" }, communicationType = CommunicationType.REST // OR GRPC depending on what's available
)
public class TestTaskExecutor extends AbstractWorkflowExecutor {

    @Override

    public Uni<tech.kayys.silat.execution.NodeExecutionResult> execute(NodeExecutionTask task) {
        System.out.println("Executing task: " + task.nodeId());
        System.out.println("Input: " + task.context());

        return Uni.createFrom().item(() -> {
            return SimpleNodeExecutionResult.success(
                    task.runId(),
                    task.nodeId(),
                    task.attempt(),
                    Map.of("result", "Task executed successfully!"),
                    task.token(),
                    java.time.Duration.ZERO);
        });
    }
}
