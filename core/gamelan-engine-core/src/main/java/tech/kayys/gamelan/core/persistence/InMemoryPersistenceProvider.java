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
    private final Map<WorkflowRunId, List<SignalContext>> signals = new ConcurrentHashMap<>();
    private final Map<WorkflowRunId, Map<NodeId, NodeResult>> nodeResults = new ConcurrentHashMap<>();

    @Override
    public void saveWorkflow(WorkflowContext workflow) {
        LOG.debug("Saving workflow run: {}", workflow.runId());
        workflows.put(workflow.runId(), workflow);
    }

    @Override
    public Optional<WorkflowContext> loadWorkflow(WorkflowRunId runId) {
        return Optional.ofNullable(workflows.get(runId));
    }

    @Override
    public void appendEvent(WorkflowRunId runId, String eventType, Object payload) {
        LOG.debug("Appending event {} to run: {}", eventType, runId);
        events.computeIfAbsent(runId, k -> Collections.synchronizedList(new ArrayList<>()))
                .add(new StoredEvent(eventType, payload, java.time.Instant.now()));
    }

    @Override
    public void saveNodeResult(WorkflowRunId runId, NodeId nodeId, NodeResult result) {
        LOG.debug("Saving result for node {} in run: {}", nodeId, runId);
        nodeResults.computeIfAbsent(runId, k -> new ConcurrentHashMap<>())
                .put(nodeId, result);
    }

    @Override
    public void saveSignal(WorkflowRunId runId, SignalContext signal) {
        LOG.debug("Saving signal for run: {}", runId);
        signals.computeIfAbsent(runId, k -> Collections.synchronizedList(new ArrayList<>()))
                .add(signal);
    }

    public List<StoredEvent> getEvents(WorkflowRunId runId) {
        return List.copyOf(events.getOrDefault(runId, List.of()));
    }

    public List<SignalContext> getSignals(WorkflowRunId runId) {
        return List.copyOf(signals.getOrDefault(runId, List.of()));
    }

    public Map<NodeId, NodeResult> getNodeResults(WorkflowRunId runId) {
        return Map.copyOf(nodeResults.getOrDefault(runId, Map.of()));
    }

    public void clear() {
        workflows.clear();
        events.clear();
        signals.clear();
        nodeResults.clear();
    }

    public record StoredEvent(String type, Object payload, java.time.Instant timestamp) {
    }
}
