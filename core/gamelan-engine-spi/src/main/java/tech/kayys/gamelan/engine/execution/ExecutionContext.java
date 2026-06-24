package tech.kayys.gamelan.engine.execution;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import tech.kayys.gamelan.engine.event.ExecutionEvent;
import tech.kayys.gamelan.engine.node.NodeExecutionState;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.payload.ExecutionPayloads;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;

/**
 * Mutable execution state for a workflow run.
 * Identity (runId, tenantId) is immutable via ExecutionIdentity.
 * Node-scoped fields (currentNodeId) are transient execution state.
 */
public class ExecutionContext {

    // --- Immutable identity ---
    private final ExecutionIdentity identity;

    // --- Transient node-scoped state (changes per node execution) ---
    private NodeId currentNodeId;

    // --- Mutable workflow-lifetime state ---
    private Map<String, Object> variables;
    private Map<String, Object> metadata;
    private Map<String, Object> workflowState;
    private final Map<NodeId, NodeExecutionState> nodeStates;
    private final List<ExecutionEvent> events;
    private Instant createdAt;
    private Instant lastUpdatedAt;
    private Instant startedAt;
    private Instant completedAt;

    public ExecutionContext(WorkflowRunId runId, TenantId tenantId, Map<String, Object> initialVariables) {
        this.identity = new ExecutionIdentity(runId, tenantId);
        this.variables = ExecutionPayloads.mutableMap(initialVariables);
        this.nodeStates = new HashMap<>();
        this.events = new ArrayList<>();
        this.metadata = ExecutionPayloads.mutableMap(null);
        this.workflowState = ExecutionPayloads.mutableMap(null);
    }

    private ExecutionContext(Builder b) {
        this.identity = new ExecutionIdentity(
                Objects.requireNonNull(b.runId, "runId required"),
                Objects.requireNonNull(b.tenantId, "tenantId required"));
        this.currentNodeId = b.currentNodeId;
        this.variables = ExecutionPayloads.mutableMap(b.variables);
        this.metadata = ExecutionPayloads.mutableMap(b.metadata);
        this.workflowState = ExecutionPayloads.mutableMap(b.workflowState);
        this.nodeStates = b.nodeStates != null ? new HashMap<>(b.nodeStates) : new HashMap<>();
        this.events = b.events != null ? new ArrayList<>(b.events) : new ArrayList<>();
        this.createdAt = b.createdAt;
        this.lastUpdatedAt = b.lastUpdatedAt;
        this.startedAt = b.startedAt;
        this.completedAt = b.completedAt;
    }

    // --- Identity accessors ---

    public ExecutionIdentity getIdentity() { return identity; }
    public WorkflowRunId getRunId() { return identity.runId(); }
    public TenantId getTenantId() { return identity.tenantId(); }

    // --- Node-scoped state ---

    public Optional<NodeId> getCurrentNodeId() { return Optional.ofNullable(currentNodeId); }
    public void setCurrentNodeId(NodeId nodeId) { this.currentNodeId = nodeId; }

    // --- Variables ---

    public void setVariable(String key, Object value) { variables.put(key, ExecutionPayloads.immutableValue(value)); }
    public Object getVariable(String key) { return variables.get(key); }
    public Map<String, Object> getVariables() { return Collections.unmodifiableMap(variables); }
    public void setVariables(Map<String, Object> variables) {
        this.variables = ExecutionPayloads.mutableMap(variables);
    }

    @SuppressWarnings("unchecked")
    public <T> T getVariable(String name, Class<T> type) {
        return variables == null ? null : (T) variables.get(name);
    }

    public <T> T getVariableOrDefault(String name, T defaultValue, Class<T> type) {
        T val = getVariable(name, type);
        return val != null ? val : defaultValue;
    }

    // --- Metadata & workflow state ---

    public Map<String, Object> getMetadata() { return Collections.unmodifiableMap(metadata); }
    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = ExecutionPayloads.mutableMap(metadata);
    }

    public Map<String, Object> getWorkflowState() { return Collections.unmodifiableMap(workflowState); }
    public void setWorkflowState(Map<String, Object> workflowState) {
        this.workflowState = ExecutionPayloads.mutableMap(workflowState);
    }

    // --- Node states ---

    public void updateNodeState(NodeId nodeId, NodeExecutionState state) { nodeStates.put(nodeId, state); }
    public Optional<NodeExecutionState> getNodeState(NodeId nodeId) { return Optional.ofNullable(nodeStates.get(nodeId)); }
    public Map<NodeId, NodeExecutionState> getAllNodeStates() { return Collections.unmodifiableMap(nodeStates); }

    // --- Events ---

    public void recordEvent(ExecutionEvent event) { events.add(event); }
    public List<ExecutionEvent> getEvents() { return Collections.unmodifiableList(events); }

    // --- Timestamps ---

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getLastUpdatedAt() { return lastUpdatedAt; }
    public void setLastUpdatedAt(Instant lastUpdatedAt) { this.lastUpdatedAt = lastUpdatedAt; }
    public Optional<Instant> getStartedAt() { return Optional.ofNullable(startedAt); }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Optional<Instant> getCompletedAt() { return Optional.ofNullable(completedAt); }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
    public void markStarted() { this.startedAt = Instant.now(); }
    public void markCompleted() { this.completedAt = Instant.now(); }

    // --- Fluent mutators ---

    public ExecutionContext withVariable(String name, Object value, String type) {
        variables.put(name, ExecutionPayloads.immutableValue(value));
        return this;
    }

    public ExecutionContext withoutVariable(String name) {
        variables.remove(name);
        return this;
    }

    public ExecutionContext withMetadata(String key, Object value) {
        metadata.put(key, ExecutionPayloads.immutableValue(value));
        return this;
    }

    public ExecutionContext withWorkflowState(Map<String, Object> updates) {
        ExecutionPayloads.mutableMap(updates).forEach(workflowState::put);
        return this;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ExecutionContext that = (ExecutionContext) o;
        return Objects.equals(identity, that.identity) &&
                Objects.equals(variables, that.variables) &&
                Objects.equals(nodeStates, that.nodeStates);
    }

    @Override
    public int hashCode() {
        return Objects.hash(identity, variables, nodeStates);
    }

    @Override
    public String toString() {
        return "ExecutionContext{runId=" + identity.runId() + ", tenantId=" + identity.tenantId() +
                ", currentNode=" + currentNodeId + "}";
    }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private WorkflowRunId runId;
        private TenantId tenantId;
        private NodeId currentNodeId;
        private Map<String, Object> variables;
        private Map<String, Object> metadata;
        private Map<String, Object> workflowState;
        private Map<NodeId, NodeExecutionState> nodeStates;
        private List<ExecutionEvent> events;
        private Instant createdAt;
        private Instant lastUpdatedAt;
        private Instant startedAt;
        private Instant completedAt;

        public Builder runId(WorkflowRunId runId) { this.runId = runId; return this; }
        public Builder tenantId(TenantId tenantId) { this.tenantId = tenantId; return this; }
        public Builder currentNodeId(NodeId currentNodeId) { this.currentNodeId = currentNodeId; return this; }
        public Builder variables(Map<String, Object> variables) { this.variables = variables; return this; }
        public Builder metadata(Map<String, Object> metadata) { this.metadata = metadata; return this; }
        public Builder workflowState(Map<String, Object> workflowState) { this.workflowState = workflowState; return this; }
        public Builder nodeStates(Map<NodeId, NodeExecutionState> nodeStates) { this.nodeStates = nodeStates; return this; }
        public Builder events(List<ExecutionEvent> events) { this.events = events; return this; }
        public Builder createdAt(Instant createdAt) { this.createdAt = createdAt; return this; }
        public Builder lastUpdatedAt(Instant lastUpdatedAt) { this.lastUpdatedAt = lastUpdatedAt; return this; }
        public Builder startedAt(Instant startedAt) { this.startedAt = startedAt; return this; }
        public Builder completedAt(Instant completedAt) { this.completedAt = completedAt; return this; }

        public ExecutionContext build() { return new ExecutionContext(this); }
    }
}
