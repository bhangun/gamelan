package tech.kayys.gamelan.kafka;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import tech.kayys.gamelan.engine.node.NodeExecutionResult;
import tech.kayys.gamelan.engine.node.NodeExecutionTask;

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
