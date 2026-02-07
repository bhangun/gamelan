package tech.kayys.gamelan.engine.workflow;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import tech.kayys.gamelan.engine.error.ErrorCode;
import tech.kayys.gamelan.engine.error.GamelanException;
import tech.kayys.gamelan.engine.node.InputDefinition;
import tech.kayys.gamelan.engine.node.NodeDefinition;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.node.OutputDefinition;
import tech.kayys.gamelan.engine.run.RetryPolicy;
import tech.kayys.gamelan.engine.saga.CompensationPolicy;
import tech.kayys.gamelan.engine.tenant.TenantId;

/**
 * Workflow Definition - Blueprint for workflow execution
 * Immutable after creation
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WorkflowDefinition(
        @JsonProperty("id") WorkflowDefinitionId id,
        @JsonProperty("tenantId") TenantId tenantId,
        @JsonProperty("name") String name,
        @JsonProperty("version") String version,
        @JsonProperty("description") String description,
        @JsonProperty("mode") WorkflowMode mode,
        @JsonProperty("nodes") List<NodeDefinition> nodes,
        @JsonProperty("inputs") Map<String, InputDefinition> inputs,
        @JsonProperty("outputs") Map<String, OutputDefinition> outputs,
        @JsonProperty("metadata") WorkflowMetadata metadata,
        @JsonProperty("defaultRetryPolicy") RetryPolicy defaultRetryPolicy,
        @JsonProperty("compensationPolicy") CompensationPolicy compensationPolicy) {

    public WorkflowDefinition {
        Objects.requireNonNull(id, "Workflow ID cannot be null");
        Objects.requireNonNull(tenantId, "Tenant ID cannot be null");
        Objects.requireNonNull(name, "Workflow name cannot be null");
        mode = mode != null ? mode : WorkflowMode.FLOW;
        nodes = nodes != null ? List.copyOf(nodes) : List.of();
        inputs = inputs != null ? Map.copyOf(inputs) : Map.of();
        outputs = outputs != null ? Map.copyOf(outputs) : Map.of();
        defaultRetryPolicy = defaultRetryPolicy != null ? defaultRetryPolicy : RetryPolicy.none();
        compensationPolicy = compensationPolicy != null ? compensationPolicy : CompensationPolicy.disabled();
    }

    // ==================== NODE ACCESS ====================

    public Optional<NodeDefinition> findNode(NodeId nodeId) {
        return nodes.stream()
                .filter(n -> n.id().equals(nodeId))
                .findFirst();
    }

    @JsonIgnore
    public List<NodeDefinition> getStartNodes() {
        return nodes.stream()
                .filter(NodeDefinition::isStartNode)
                .toList();
    }

    @JsonIgnore
    public List<NodeDefinition> getEndNodes() {
        return nodes.stream()
                .filter(NodeDefinition::isEndNode)
                .toList();
    }

    // ==================== VALIDATION ====================

    @JsonIgnore
    public boolean isValid() {
        return hasAtLeastOneStartNode()
                && hasNoCircularDependenciesIfDag()
                && hasValidDependencies()
                && hasValidIO();
    }

    private boolean hasAtLeastOneStartNode() {
        return !getStartNodes().isEmpty();
    }

    private boolean hasNoCircularDependenciesIfDag() {
        if (mode != WorkflowMode.DAG) {
            return true;
        }
        // Simple DFS cycle detection
        Map<NodeId, List<NodeId>> graph = buildDependencyGraph();
        Set<NodeId> visited = new HashSet<>();
        Set<NodeId> stack = new HashSet<>();

        for (NodeId nodeId : graph.keySet()) {
            if (detectCycle(nodeId, graph, visited, stack)) {
                return false;
            }
        }
        return true;
    }

    private boolean detectCycle(
            NodeId nodeId,
            Map<NodeId, List<NodeId>> graph,
            Set<NodeId> visited,
            Set<NodeId> stack) {

        if (stack.contains(nodeId))
            return true;
        if (visited.contains(nodeId))
            return false;

        visited.add(nodeId);
        stack.add(nodeId);

        for (NodeId dep : graph.getOrDefault(nodeId, List.of())) {
            if (detectCycle(dep, graph, visited, stack)) {
                return true;
            }
        }

        stack.remove(nodeId);
        return false;
    }

    private Map<NodeId, List<NodeId>> buildDependencyGraph() {
        Map<NodeId, List<NodeId>> graph = new HashMap<>();
        for (NodeDefinition node : nodes) {
            graph.put(node.id(), List.copyOf(node.dependsOn()));
        }
        return graph;
    }

    private boolean hasValidDependencies() {
        Set<NodeId> nodeIds = nodes.stream()
                .map(NodeDefinition::id)
                .collect(Collectors.toSet());

        return nodes.stream()
                .flatMap(n -> n.dependsOn().stream())
                .allMatch(nodeIds::contains);
    }

    private boolean hasValidIO() {
        // Inputs referenced must exist
        return inputs.keySet().stream().allMatch(Objects::nonNull)
                && outputs.keySet().stream().allMatch(Objects::nonNull);
    }

    // ==================== COMPENSATION ====================

    @JsonIgnore
    public boolean isCompensationEnabled() {
        return compensationPolicy != null && compensationPolicy.enabled();
    }

    // ==================== DEBUG / INTROSPECTION ====================

    public int nodeCount() {
        return nodes.size();
    }

    public Set<NodeId> allNodeIds() {
        return nodes.stream()
                .map(NodeDefinition::id)
                .collect(Collectors.toUnmodifiableSet());
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private WorkflowDefinitionId id;
        private TenantId tenantId;
        private String name;
        private String version;
        private String description;
        private WorkflowMode mode = WorkflowMode.FLOW;
        private List<NodeDefinition> nodes = new ArrayList<>();
        private Map<String, InputDefinition> inputs = new HashMap<>();
        private Map<String, OutputDefinition> outputs = new HashMap<>();
        private WorkflowMetadata metadata;
        private RetryPolicy defaultRetryPolicy;
        private CompensationPolicy compensationPolicy;

        public Builder id(WorkflowDefinitionId id) {
            this.id = id;
            return this;
        }

        public Builder tenantId(TenantId tenantId) {
            this.tenantId = tenantId;
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder version(String version) {
            this.version = version;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }
        
        public Builder mode(WorkflowMode mode) {
            this.mode = mode != null ? mode : WorkflowMode.FLOW;
            return this;
        }

        public Builder nodes(List<NodeDefinition> nodes) {
            this.nodes = new ArrayList<>(nodes);
            return this;
        }

        public Builder addNode(NodeDefinition node) {
            this.nodes.add(node);
            return this;
        }

        public Builder inputs(Map<String, InputDefinition> inputs) {
            this.inputs = new HashMap<>(inputs);
            return this;
        }

        public Builder addInput(String key, InputDefinition value) {
            this.inputs.put(key, value);
            return this;
        }

        public Builder outputs(Map<String, OutputDefinition> outputs) {
            this.outputs = new HashMap<>(outputs);
            return this;
        }

        public Builder addOutput(String key, OutputDefinition value) {
            this.outputs.put(key, value);
            return this;
        }

        public Builder metadata(WorkflowMetadata metadata) {
            this.metadata = metadata;
            return this;
        }

        public Builder defaultRetryPolicy(RetryPolicy defaultRetryPolicy) {
            this.defaultRetryPolicy = defaultRetryPolicy;
            return this;
        }

        public Builder compensationPolicy(CompensationPolicy compensationPolicy) {
            this.compensationPolicy = compensationPolicy;
            return this;
        }

        public WorkflowDefinition build() {
            return new WorkflowDefinition(
                    id,
                    tenantId,
                    name,
                    version,
                    description,
                    mode,
                    nodes,
                    inputs,
                    outputs,
                    metadata,
                    defaultRetryPolicy,
                    compensationPolicy);
        }

        public WorkflowDefinition buildAndValidate() {
            WorkflowDefinition workflowDefinition = build();
            if (!workflowDefinition.isValid()) {
                throw new GamelanException(ErrorCode.WORKFLOW_INVALID_DEFINITION, "Invalid workflow definition");
            }
            return workflowDefinition;
        }
    }

}
