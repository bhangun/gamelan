package tech.kayys.gamelan.core.persistence;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.enterprise.context.ApplicationScoped;
import tech.kayys.gamelan.engine.context.WorkflowContext;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.node.NodeResult;
import tech.kayys.gamelan.engine.persistence.PersistenceProvider;
import tech.kayys.gamelan.engine.signal.SignalContext;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;

@ApplicationScoped
public class InMemoryPersistenceProvider implements PersistenceProvider {

    private final Map<WorkflowRunId, WorkflowContext> workflows = new ConcurrentHashMap<>();

    @Override
    public void saveWorkflow(WorkflowContext workflow) {
        workflows.put(workflow.runId(), workflow);
    }

    @Override
    public Optional<WorkflowContext> loadWorkflow(WorkflowRunId runId) {
        return Optional.ofNullable(workflows.get(runId));
    }

    @Override
    public void appendEvent(WorkflowRunId runId, String eventType, Object payload) {
        // event sourcing hook
    }

    @Override
    public void saveNodeResult(WorkflowRunId runId, NodeId nodeId, NodeResult result) {
        WorkflowContext ctx = workflows.get(runId);
        if (ctx != null) {
            ctx.markNodeCompleted(nodeId, result);
        }
    }

    @Override
    public void saveSignal(WorkflowRunId runId, SignalContext signal) {
        // store external signals
    }
}
