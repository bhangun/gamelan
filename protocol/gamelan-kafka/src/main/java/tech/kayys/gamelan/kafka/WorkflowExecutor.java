package tech.kayys.gamelan.kafka;

import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.engine.node.NodeExecutionResult;
import tech.kayys.gamelan.engine.node.NodeExecutionTask;

/**
 * Workflow executor interface
 */
public interface WorkflowExecutor {
    Uni<NodeExecutionResult> execute(NodeExecutionTask task);

    String executorType();
}
