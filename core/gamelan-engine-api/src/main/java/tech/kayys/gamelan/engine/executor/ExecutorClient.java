package tech.kayys.gamelan.engine.executor;

import java.util.Map;
import java.util.concurrent.CompletionStage;

import tech.kayys.gamelan.engine.node.NodeContext;
import tech.kayys.gamelan.engine.node.NodeResult;

public interface ExecutorClient {

    String executorType(); // e.g. "http", "grpc", "python"

    CompletionStage<NodeResult> execute(
            NodeContext node,
            Map<String, Object> workflowVariables);
}
