package tech.kayys.gamelan.engine.node;

public interface NodeTypeHandler {

    String nodeType(); // e.g. "http", "llm", "bpmn", "custom"

    NodeResult execute(NodeExecutionContext ctx);
}
