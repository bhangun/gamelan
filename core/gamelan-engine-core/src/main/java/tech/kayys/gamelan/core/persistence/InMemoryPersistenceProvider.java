package tech.kayys.gamelan.core.persistence;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.enterprise.context.ApplicationScoped;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tech.kayys.gamelan.engine.context.WorkflowContext;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.node.NodeResult;
import tech.kayys.gamelan.engine.persistence.PersistenceProvider;
import tech.kayys.gamelan.engine.signal.SignalContext;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;

@ApplicationScoped
public class InMemoryPersistenceProvider implements PersistenceProvider {

    private static final Logger LOG = LoggerFactory.getLogger(InMemoryPersistenceProvider.class);

    private final Map<WorkflowRunId, WorkflowContext> workflows = new ConcurrentHashMap<>();
    private final Map<WorkflowRunId, List<StoredEvent>> events = new ConcurrentHashMap<>();
    private final Map<WorkflowRunId, Map<NodeId, NodeResult>> nodeResults = new ConcurrentHashMap<>();
    private final Map<WorkflowRunId, List<SignalContext>> signals = new ConcurrentHashMap<>();

    @Override
    public void saveWorkflow(WorkflowContext workflow) {
        LOG.debug("Saving workflow run: {}", workflow.getRunId().value());
        workflows.put(workflow.getRunId(), workflow);
    }

    @Override
    public Optional<WorkflowContext> loadWorkflow(WorkflowRunId runId) {
        LOG.debug("Loading workflow run: {}", runId.value());
        return Optional.ofNullable(workflows.get(runId));
    }

    @Override
    public void appendEvent(WorkflowRunId runId, String eventType, Object payload) {
        LOG.debug("Appending event {} to run: {}", eventType, runId.value());
        events.computeIfAbsent(runId, k -> Collections.synchronizedList(new ArrayList<>()))
                .add(new StoredEvent(eventType, payload));
    }

    @Override
    public void saveNodeResult(WorkflowRunId runId, NodeId nodeId, NodeResult result) {
        LOG.debug("Saving result for node {} in run: {}", nodeId, runId);
        nodeResults.computeIfAbsent(runId, k -> new ConcurrentHashMap<>())
                .put(nodeId, result);
    }

    @Override
    public void saveSignal(WorkflowRunId runId, SignalContext signal) {
        LOG.debug("Saving signal for run: {}", runId.value());
        signals.computeIfAbsent(runId, k -> Collections.synchronizedList(new ArrayList<>()))
                .add(signal);
    }

    @Override
    public void updateContextVariable(WorkflowRunId runId, String key, Object value) {
        WorkflowContext workflow = workflows.get(runId);
        if (workflow != null) {
            workflow.getVariables().put(key, value);
        }
    }

    @Override
    public void updateNodeExecution(WorkflowRunId runId, NodeId nodeId,
            tech.kayys.gamelan.engine.node.NodeExecutionSnapshot snapshot) {
        LOG.debug("Updating node execution snapshot for node {} in run: {}", nodeId, runId);
    }

    public List<StoredEvent> getEvents(WorkflowRunId runId) {
        return events.getOrDefault(runId, Collections.emptyList());
    }

    public static record StoredEvent(String type, Object payload) {
    }
}
