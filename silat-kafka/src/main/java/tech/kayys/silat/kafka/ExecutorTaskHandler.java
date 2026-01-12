package tech.kayys.silat.kafka;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import tech.kayys.silat.execution.NodeExecutionResult;
import tech.kayys.silat.execution.NodeExecutionTask;

/**
 * Executor task handler interface
 */
@ApplicationScoped
public class ExecutorTaskHandler {

    @Inject
    WorkflowExecutorRegistry executorRegistry;

    public Uni<NodeExecutionResult> executeTask(NodeExecutionTask task) {
        // Find appropriate executor and execute
        return executorRegistry.getExecutor(task.nodeId())
                .flatMap(executor -> executor.execute(task));
    }
}
