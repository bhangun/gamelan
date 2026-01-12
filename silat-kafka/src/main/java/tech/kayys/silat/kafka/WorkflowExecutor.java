package tech.kayys.silat.kafka;

import io.smallrye.mutiny.Uni;
import tech.kayys.silat.execution.NodeExecutionResult;
import tech.kayys.silat.execution.NodeExecutionTask;

/**
 * Workflow executor interface
 */
public interface WorkflowExecutor {
    Uni<NodeExecutionResult> execute(NodeExecutionTask task);

    String executorType();
}
