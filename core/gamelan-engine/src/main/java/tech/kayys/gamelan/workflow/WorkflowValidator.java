package tech.kayys.gamelan.workflow;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import tech.kayys.gamelan.engine.node.NodeDefinition;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.run.ValidationResult;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinition;

/**
 * Workflow validator - Uses dependency-based structure (dependsOn)
 */
@ApplicationScoped
public class WorkflowValidator {

    public Uni<ValidationResult> validate(WorkflowDefinition workflow) {
        List<String> errors = new ArrayList<>();

        // Check basic structure
        if (workflow.nodes() == null || workflow.nodes().isEmpty()) {
            errors.add("Workflow must have at least one node");
        }

        // Validate dependency references
        if (workflow.nodes() != null) {
            Set<NodeId> nodeIds = workflow.nodes().stream()
                    .map(NodeDefinition::id)
                    .collect(Collectors.toSet());

            for (NodeDefinition node : workflow.nodes()) {
                for (NodeId dependency : node.dependsOn()) {
                    if (!nodeIds.contains(dependency)) {
                        errors.add(
                                "Node " + node.id().value() + " references unknown dependency: " + dependency.value());
                    }
                }
            }
        }

        // Check for cycles
        if (workflow.nodes() != null) {
            if (hasCycles(workflow)) {
                errors.add("Workflow contains cycles (not supported)");
            }
        }

        if (!errors.isEmpty()) {
            return Uni.createFrom().item(
                    ValidationResult.failure(String.join("; ", errors)));
        }

        return Uni.createFrom().item(ValidationResult.success());
    }

    private boolean hasCycles(WorkflowDefinition workflow) {
        // Simple cycle detection using DFS
        Map<NodeId, Set<NodeId>> adjacency = new HashMap<>();

        for (NodeDefinition node : workflow.nodes()) {
            for (NodeId dependency : node.dependsOn()) {
                // Dependency model: dependency -> node
                adjacency.computeIfAbsent(dependency, k -> new HashSet<>())
                        .add(node.id());
            }
        }

        Set<NodeId> visited = new HashSet<>();
        Set<NodeId> recursionStack = new HashSet<>();

        for (NodeDefinition node : workflow.nodes()) {
            if (hasCycleDFS(node.id(), adjacency, visited, recursionStack)) {
                return true;
            }
        }

        return false;
    }

    private boolean hasCycleDFS(
            NodeId nodeId,
            Map<NodeId, Set<NodeId>> adjacency,
            Set<NodeId> visited,
            Set<NodeId> recursionStack) {

        if (recursionStack.contains(nodeId)) {
            return true;
        }

        if (visited.contains(nodeId)) {
            return false;
        }

        visited.add(nodeId);
        recursionStack.add(nodeId);

        Set<NodeId> neighbors = adjacency.get(nodeId);
        if (neighbors != null) {
            for (NodeId neighbor : neighbors) {
                if (hasCycleDFS(neighbor, adjacency, visited, recursionStack)) {
                    return true;
                }
            }
        }

        recursionStack.remove(nodeId);
        return false;
    }
}