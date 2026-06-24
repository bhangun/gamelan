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
import tech.kayys.gamelan.engine.executor.ExecutorSelectionPolicy;
import tech.kayys.gamelan.engine.node.InputDefinition;
import tech.kayys.gamelan.engine.node.NodeDefinition;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.node.OutputDefinition;
import tech.kayys.gamelan.engine.run.RetryPolicy;
import tech.kayys.gamelan.engine.run.Transition;
import tech.kayys.gamelan.engine.run.ValidationResult;
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
        metadata = metadata != null ? metadata : WorkflowMetadata.system();
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
        return validate().isValid();
    }

    @JsonIgnore
    public ValidationResult validate() {
        List<String> errors = new ArrayList<>();

        validateNodes(errors);
        validateExecutorSelection(errors);
        validateDependencies(errors);
        validateTransitions(errors);
        validateIO(errors);

        if (mode == WorkflowMode.DAG && hasCircularDependencies()) {
            errors.add("DAG workflow contains circular dependencies");
        }

        if (errors.isEmpty()) {
            return ValidationResult.success();
        }
        return ValidationResult.failure("Invalid workflow definition", errors);
    }

    private void validateNodes(List<String> errors) {
        if (nodes.isEmpty()) {
            errors.add("Workflow must have at least one node");
            return;
        }

        Set<NodeId> seen = new HashSet<>();
        for (NodeDefinition node : nodes) {
            if (node.id().value().isBlank()) {
                errors.add("Node id cannot be blank");
            }
            if (!seen.add(node.id())) {
                errors.add("Duplicate node id: " + node.id().value());
            }
        }

        if (getStartNodes().isEmpty()) {
            errors.add("Workflow must have at least one start node");
        }
    }

    private void validateExecutorSelection(List<String> errors) {
        for (NodeDefinition node : nodes) {
            try {
                ExecutorSelectionPolicy policy = ExecutorSelectionPolicy.fromContext(node.configuration());
                for (String error : policy.validationErrors()) {
                    errors.add("Node " + node.id().value() + " has invalid executor selection: " + error);
                }
            } catch (GamelanException error) {
                errors.add("Node " + node.id().value()
                        + " has invalid executor selection: "
                        + error.getSafeMessage());
            }
        }
    }

    private boolean hasCircularDependencies() {
        Map<NodeId, List<NodeId>> graph = buildDependencyGraph();
        Set<NodeId> visited = new HashSet<>();
        Set<NodeId> stack = new HashSet<>();

        for (NodeId nodeId : graph.keySet()) {
            if (detectCycle(nodeId, graph, visited, stack)) {
                return true;
            }
        }
        return false;
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

    private void validateDependencies(List<String> errors) {
        Set<NodeId> nodeIds = nodes.stream()
                .map(NodeDefinition::id)
                .collect(Collectors.toSet());

        for (NodeDefinition node : nodes) {
            for (NodeId dependency : node.dependsOn()) {
                if (node.id().equals(dependency)) {
                    errors.add("Node " + node.id().value() + " cannot depend on itself");
                }
                if (!nodeIds.contains(dependency)) {
                    errors.add("Node " + node.id().value()
                            + " references unknown dependency: " + dependency.value());
                }
            }
        }
    }

    private void validateTransitions(List<String> errors) {
        Set<NodeId> nodeIds = nodes.stream()
                .map(NodeDefinition::id)
                .collect(Collectors.toSet());

        for (NodeDefinition node : nodes) {
            for (Transition transition : node.transitions()) {
                if (transition.type() == null) {
                    errors.add("Node " + node.id().value() + " has transition without type");
                }
                if (transition.targetNodeId() != null && !nodeIds.contains(transition.targetNodeId())) {
                    errors.add("Node " + node.id().value()
                            + " transitions to unknown node: " + transition.targetNodeId().value());
                }
            }
        }
    }

    private void validateIO(List<String> errors) {
        inputs.keySet().stream()
                .filter(key -> key == null || key.isBlank())
                .forEach(key -> errors.add("Workflow input name cannot be blank"));
        outputs.keySet().stream()
                .filter(key -> key == null || key.isBlank())
                .forEach(key -> errors.add("Workflow output name cannot be blank"));
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
            ValidationResult validation = workflowDefinition.validate();
            if (!validation.isValid()) {
                throw new GamelanException(
                        ErrorCode.WORKFLOW_INVALID_DEFINITION,
                        validation.message() + ": " + String.join("; ", validation.errors()));
            }
            return workflowDefinition;
        }
    }

}
