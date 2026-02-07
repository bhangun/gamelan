package tech.kayys.gamelan.engine.node;

import tech.kayys.gamelan.engine.context.EngineContext;
import tech.kayys.gamelan.engine.context.WorkflowContext;

public interface NodeExecutionContext {

    EngineContext engine();

    WorkflowContext workflow();

    void emitEvent(String type, Object payload);

    void setVariable(String key, Object value);

    void suspend(String reason);
}