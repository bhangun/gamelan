package tech.kayys.gamelan.core.engine;

import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.engine.context.EngineContext;
import tech.kayys.gamelan.engine.node.NodeContext;
import tech.kayys.gamelan.engine.node.NodeExecutionContext;
import tech.kayys.gamelan.engine.node.NodeResult;

public interface WorkflowEngine {
    void initialize(EngineContext context);
    Uni<NodeResult> executeNode(NodeContext nodeContext, NodeExecutionContext executionContext);
}
