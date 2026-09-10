package tech.kayys.gamelan.engine.node;

import java.util.Map;

import tech.kayys.gamelan.engine.context.EngineContext;
import tech.kayys.gamelan.engine.context.WorkflowContext;

public interface NodeExecutionContext {

    EngineContext engine();

    WorkflowContext workflow();

    default NodeContext node() {
        throw new UnsupportedOperationException("Current node context is not available");
    }

    default Map<String, Object> variables() {
        return workflow().variables();
    }

    void emitEvent(String type, Object payload);

    void setVariable(String key, Object value);

    void suspend(String reason);
}
