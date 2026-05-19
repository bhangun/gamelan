package tech.kayys.gamelan.core.node;

import java.util.Map;
import java.util.HashMap;
import java.util.Collections;
import tech.kayys.gamelan.engine.context.EngineContext;
import tech.kayys.gamelan.engine.context.WorkflowContext;
import tech.kayys.gamelan.engine.node.NodeExecutionContext;
import tech.kayys.gamelan.plugin.event.GenericPluginEvent;

public class DefaultNodeExecutionContext implements NodeExecutionContext {

    private final EngineContext engine;
    private final WorkflowContext workflow;
    private final tech.kayys.gamelan.engine.node.NodeContext node;
    private final Map<String, Object> addedVariables = new HashMap<>();
    private String suspendReason;

    public DefaultNodeExecutionContext(EngineContext engine, WorkflowContext workflow) {
        this(engine, workflow, null);
    }

    public DefaultNodeExecutionContext(
            EngineContext engine,
            WorkflowContext workflow,
            tech.kayys.gamelan.engine.node.NodeContext node) {
        this.engine = engine;
        this.workflow = workflow;
        this.node = node;
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
    public tech.kayys.gamelan.engine.node.NodeContext node() {
        if (node == null) {
            return NodeExecutionContext.super.node();
        }
        return node;
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
        addedVariables.put(key, value);
    }

    @Override
    public void suspend(String reason) {
        this.suspendReason = reason;
    }

    public Map<String, Object> getAddedVariables() {
        return Collections.unmodifiableMap(addedVariables);
    }

    public String getSuspendReason() {
        return suspendReason;
    }
}
