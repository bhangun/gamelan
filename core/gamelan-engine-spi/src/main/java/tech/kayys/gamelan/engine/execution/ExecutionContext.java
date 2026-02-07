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
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;

/**
 * 🔒 Execution context with strong typing.
 * Opaque to kernel - plugins interpret variables.
 */
public class ExecutionContext {

    private String executionId;
    private String workflowRunId;
    private String nodeId;
    private Map<String, Object> variables;
    private Map<String, Object> metadata;
    private Map<String, Object> workflowState;
    private Instant createdAt;
    private Instant lastUpdatedAt;

    private final WorkflowRunId runId;
    private final TenantId tenantId;
    private final Map<NodeId, NodeExecutionState> nodeStates;
    private final List<ExecutionEvent> events;
    private Instant startedAt;
    private Instant completedAt;

    public ExecutionContext(WorkflowRunId runId, TenantId tenantId, Map<String, Object> initialVariables) {
        this.runId = Objects.requireNonNull(runId);
        this.tenantId = Objects.requireNonNull(tenantId);
        this.variables = new HashMap<>(initialVariables != null ? initialVariables : Map.of());
        this.nodeStates = new HashMap<>();
        this.events = new ArrayList<>();
        this.metadata = new HashMap<>();
        this.workflowState = new HashMap<>();
    }

    public ExecutionContext(String executionId, String workflowRunId, String nodeId,
            Map<String, Object> variables, Map<String, Object> metadata,
            Map<String, Object> workflowState, Instant createdAt, Instant lastUpdatedAt,
            WorkflowRunId runId, TenantId tenantId, Map<NodeId, NodeExecutionState> nodeStates,
            List<ExecutionEvent> events, Instant startedAt, Instant completedAt) {
        this.executionId = executionId;
        this.workflowRunId = workflowRunId;
        this.nodeId = nodeId;
        this.variables = variables != null ? new HashMap<>(variables) : new HashMap<>();
        this.metadata = metadata != null ? new HashMap<>(metadata) : new HashMap<>();
        this.workflowState = workflowState != null ? new HashMap<>(workflowState) : new HashMap<>();
        this.createdAt = createdAt;
        this.lastUpdatedAt = lastUpdatedAt;
        this.runId = runId;
        this.tenantId = tenantId;
        this.nodeStates = nodeStates != null ? new HashMap<>(nodeStates) : new HashMap<>();
        this.events = events != null ? new ArrayList<>(events) : new ArrayList<>();
        this.startedAt = startedAt;
        this.completedAt = completedAt;
    }

    public static Builder builder() {
        return new Builder();
    }

    public WorkflowRunId getRunId() {
        return runId;
    }

    public TenantId getTenantId() {
        return tenantId;
    }

    public String getExecutionId() {
        return executionId;
    }

    public void setExecutionId(String executionId) {
        this.executionId = executionId;
    }

    public String getWorkflowRunId() {
        return workflowRunId;
    }

    public void setWorkflowRunId(String workflowRunId) {
        this.workflowRunId = workflowRunId;
    }

    public String getNodeId() {
        return nodeId;
    }

    public void setNodeId(String nodeId) {
        this.nodeId = nodeId;
    }

    public void setVariable(String key, Object value) {
        variables.put(key, value);
    }

    public Object getVariable(String key) {
        return variables.get(key);
    }

    public Map<String, Object> getVariables() {
        return variables != null ? Collections.unmodifiableMap(variables) : Map.of();
    }

    public void setVariables(Map<String, Object> variables) {
        this.variables = variables != null ? new HashMap<>(variables) : new HashMap<>();
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata != null ? new HashMap<>(metadata) : new HashMap<>();
    }

    public Map<String, Object> getWorkflowState() {
        return workflowState;
    }

    public void setWorkflowState(Map<String, Object> workflowState) {
        this.workflowState = workflowState != null ? new HashMap<>(workflowState) : new HashMap<>();
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getLastUpdatedAt() {
        return lastUpdatedAt;
    }

    public void setLastUpdatedAt(Instant lastUpdatedAt) {
        this.lastUpdatedAt = lastUpdatedAt;
    }

    public void updateNodeState(NodeId nodeId, NodeExecutionState state) {
        nodeStates.put(nodeId, state);
    }

    public Optional<NodeExecutionState> getNodeState(NodeId nodeId) {
        return Optional.ofNullable(nodeStates.get(nodeId));
    }

    public Map<NodeId, NodeExecutionState> getAllNodeStates() {
        return nodeStates != null ? Collections.unmodifiableMap(nodeStates) : Map.of();
    }

    public void recordEvent(ExecutionEvent event) {
        events.add(event);
    }

    public List<ExecutionEvent> getEvents() {
        return events != null ? Collections.unmodifiableList(events) : List.of();
    }

    public void markStarted() {
        this.startedAt = Instant.now();
    }

    public void markCompleted() {
        this.completedAt = Instant.now();
    }

    public Optional<Instant> getStartedAt() {
        return Optional.ofNullable(startedAt);
    }

    public Optional<Instant> getCompletedAt() {
        return Optional.ofNullable(completedAt);
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    @SuppressWarnings("unchecked")
    public <T> T getVariable(String name, Class<T> type) {
        if (variables == null)
            return null;
        Object val = variables.get(name);
        return (T) val;
    }

    public <T> T getVariableOrDefault(String name, T defaultValue, Class<T> type) {
        T val = getVariable(name, type);
        return val != null ? val : defaultValue;
    }

    public ExecutionContext withVariable(String name, Object value, String type) {
        if (variables == null)
            variables = new HashMap<>();
        variables.put(name, value);
        return this;
    }

    public ExecutionContext withoutVariable(String name) {
        if (variables != null) {
            variables.remove(name);
        }
        return this;
    }

    public ExecutionContext withMetadata(String key, Object value) {
        if (metadata == null)
            metadata = new HashMap<>();
        metadata.put(key, value);
        return this;
    }

    public ExecutionContext withWorkflowState(Map<String, Object> updates) {
        if (workflowState == null)
            workflowState = new HashMap<>();
        workflowState.putAll(updates);
        return this;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        ExecutionContext that = (ExecutionContext) o;
        return Objects.equals(executionId, that.executionId) &&
                Objects.equals(workflowRunId, that.workflowRunId) &&
                Objects.equals(nodeId, that.nodeId) &&
                Objects.equals(variables, that.variables) &&
                Objects.equals(metadata, that.metadata) &&
                Objects.equals(workflowState, that.workflowState) &&
                Objects.equals(createdAt, that.createdAt) &&
                Objects.equals(lastUpdatedAt, that.lastUpdatedAt) &&
                Objects.equals(runId, that.runId) &&
                Objects.equals(tenantId, that.tenantId) &&
                Objects.equals(nodeStates, that.nodeStates) &&
                Objects.equals(events, that.events) &&
                Objects.equals(startedAt, that.startedAt) &&
                Objects.equals(completedAt, that.completedAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(executionId, workflowRunId, nodeId, variables, metadata, workflowState,
                createdAt, lastUpdatedAt, runId, tenantId, nodeStates, events, startedAt, completedAt);
    }

    @Override
    public String toString() {
        return "ExecutionContext{" +
                "executionId=" + executionId +
                ", workflowRunId=" + workflowRunId +
                ", nodeId=" + nodeId +
                ", runId=" + runId +
                ", tenantId=" + tenantId +
                "}";
    }

    public static class Builder {
        private String executionId;
        private String workflowRunId;
        private String nodeId;
        private Map<String, Object> variables;
        private Map<String, Object> metadata;
        private Map<String, Object> workflowState;
        private Instant createdAt;
        private Instant lastUpdatedAt;
        private WorkflowRunId runId;
        private TenantId tenantId;
        private Map<NodeId, NodeExecutionState> nodeStates;
        private List<ExecutionEvent> events;
        private Instant startedAt;
        private Instant completedAt;

        public Builder executionId(String executionId) {
            this.executionId = executionId;
            return this;
        }

        public Builder workflowRunId(String workflowRunId) {
            this.workflowRunId = workflowRunId;
            return this;
        }

        public Builder nodeId(String nodeId) {
            this.nodeId = nodeId;
            return this;
        }

        public Builder variables(Map<String, Object> variables) {
            this.variables = variables;
            return this;
        }

        public Builder metadata(Map<String, Object> metadata) {
            this.metadata = metadata;
            return this;
        }

        public Builder workflowState(Map<String, Object> workflowState) {
            this.workflowState = workflowState;
            return this;
        }

        public Builder createdAt(Instant createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public Builder lastUpdatedAt(Instant lastUpdatedAt) {
            this.lastUpdatedAt = lastUpdatedAt;
            return this;
        }

        public Builder runId(WorkflowRunId runId) {
            this.runId = runId;
            return this;
        }

        public Builder tenantId(TenantId tenantId) {
            this.tenantId = tenantId;
            return this;
        }

        public Builder nodeStates(Map<NodeId, NodeExecutionState> nodeStates) {
            this.nodeStates = nodeStates;
            return this;
        }

        public Builder events(List<ExecutionEvent> events) {
            this.events = events;
            return this;
        }

        public Builder startedAt(Instant startedAt) {
            this.startedAt = startedAt;
            return this;
        }

        public Builder completedAt(Instant completedAt) {
            this.completedAt = completedAt;
            return this;
        }

        public ExecutionContext build() {
            return new ExecutionContext(executionId, workflowRunId, nodeId, variables, metadata,
                    workflowState, createdAt, lastUpdatedAt, runId, tenantId,
                    nodeStates, events, startedAt, completedAt);
        }
    }
}
