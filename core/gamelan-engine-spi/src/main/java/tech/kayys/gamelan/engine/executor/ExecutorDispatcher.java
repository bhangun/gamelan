package tech.kayys.gamelan.engine.executor;

import java.util.concurrent.CompletionStage;

import tech.kayys.gamelan.engine.node.NodeContext;
import tech.kayys.gamelan.engine.node.NodeExecutionContext;
import tech.kayys.gamelan.engine.node.NodeResult;

public interface ExecutorDispatcher {

    CompletionStage<NodeResult> dispatch(
            NodeContext nodeContext,
            NodeExecutionContext executionContext);
}