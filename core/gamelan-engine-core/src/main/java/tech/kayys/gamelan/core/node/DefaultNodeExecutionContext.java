package tech.kayys.gamelan.core.node;

import tech.kayys.gamelan.engine.context.EngineContext;
import tech.kayys.gamelan.engine.context.WorkflowContext;
import tech.kayys.gamelan.engine.node.NodeExecutionContext;
import tech.kayys.gamelan.plugin.event.GenericPluginEvent;

public class DefaultNodeExecutionContext implements NodeExecutionContext {

    private final EngineContext engine;
    private final WorkflowContext workflow;

    public DefaultNodeExecutionContext(EngineContext engine, WorkflowContext workflow) {
        this.engine = engine;
        this.workflow = workflow;
    }

    @Override
    public EngineContext engine() {
        return engine;
    }

    @Override
    public WorkflowContext workflow() {
        return workflow;
    }

    @Override
    public void emitEvent(String type, Object payload) {
        engine.eventBus().publish(new GenericPluginEvent(
                "workflow-" + workflow.runId(),
                type,
                payload,
                null));
    }

    @Override
    public void setVariable(String key, Object value) {
        workflow.setVariable(key, value);
    }

    @Override
    public void suspend(String reason) {
        workflow.suspend(reason);
    }
}
