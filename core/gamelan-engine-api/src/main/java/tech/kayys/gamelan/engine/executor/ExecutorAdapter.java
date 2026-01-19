package tech.kayys.gamelan.engine.executor;

import tech.kayys.gamelan.engine.execution.ExecutionRequest;
import tech.kayys.gamelan.engine.execution.ExecutionResponse;
import tech.kayys.gamelan.engine.node.NodeExecutionContext;
import tech.kayys.gamelan.engine.node.NodeResult;

public interface ExecutorAdapter {

    String nodeType(); // "llm", "http", "bpmn"

    String executorType(); // matches executor registration

    ExecutionRequest buildRequest(NodeExecutionContext ctx);

    NodeResult mapResult(ExecutionResponse response);
}