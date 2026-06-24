package tech.kayys.gamelan.workflow;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import tech.kayys.gamelan.engine.node.NodeDefinition;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinition;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinitionId;
import tech.kayys.gamelan.engine.workflow.WorkflowMode;

/**
 * Precomputed workflow topology used by runtime planning.
 */
public record CompiledWorkflowDefinition(
        WorkflowDefinitionId definitionId,
        String version,
        WorkflowMode mode,
        List<NodeDefinition> orderedNodes,
        Map<NodeId, NodeDefinition> nodesById,
        Map<NodeId, List<NodeId>> dependenciesByNode,
        Map<NodeId, List<NodeId>> dependentsByNode,
        List<NodeId> startNodeIds) {

    public CompiledWorkflowDefinition {
        orderedNodes = orderedNodes != null ? List.copyOf(orderedNodes) : List.of();
        nodesById = immutableMap(nodesById);
        dependenciesByNode = immutableListMap(dependenciesByNode);
        dependentsByNode = immutableListMap(dependentsByNode);
        startNodeIds = startNodeIds != null ? List.copyOf(startNodeIds) : List.of();
    }

    public static CompiledWorkflowDefinition compile(WorkflowDefinition definition) {
        if (definition == null) {
            throw new IllegalArgumentException("WorkflowDefinition cannot be null");
        }

        List<NodeDefinition> nodes = definition.nodes() != null ? definition.nodes() : List.of();
        Map<NodeId, NodeDefinition> nodesById = indexNodes(nodes);
        Map<NodeId, List<NodeId>> dependenciesByNode = dependenciesByNode(nodes);
        Map<NodeId, List<NodeId>> dependentsByNode = dependentsByNode(nodesById, dependenciesByNode);
        List<NodeDefinition> orderedNodes = definition.mode() == WorkflowMode.DAG
                ? dagOrderOrOriginal(nodes, dependenciesByNode, dependentsByNode)
                : nodes;

        return new CompiledWorkflowDefinition(
                definition.id(),
                definition.version(),
                definition.mode(),
                orderedNodes,
                nodesById,
                dependenciesByNode,
                dependentsByNode,
                startNodeIds(nodes));
    }

    public Optional<NodeDefinition> node(NodeId nodeId) {
        return Optional.ofNullable(nodesById.get(nodeId));
    }

    public List<NodeId> dependencies(NodeId nodeId) {
        return dependenciesByNode.getOrDefault(nodeId, List.of());
    }

    public int nodeCount() {
        return orderedNodes.size();
    }

    private static Map<NodeId, NodeDefinition> indexNodes(List<NodeDefinition> nodes) {
        Map<NodeId, NodeDefinition> index = new LinkedHashMap<>();
        for (NodeDefinition node : nodes) {
            if (node != null && node.id() != null) {
                index.putIfAbsent(node.id(), node);
            }
        }
        return index;
    }

    private static Map<NodeId, List<NodeId>> dependenciesByNode(List<NodeDefinition> nodes) {
        Map<NodeId, List<NodeId>> dependencies = new LinkedHashMap<>();
        for (NodeDefinition node : nodes) {
            if (node != null && node.id() != null) {
                dependencies.putIfAbsent(node.id(), dependenciesOf(node));
            }
        }
        return dependencies;
    }

    private static Map<NodeId, List<NodeId>> dependentsByNode(
            Map<NodeId, NodeDefinition> nodesById,
            Map<NodeId, List<NodeId>> dependenciesByNode) {

        Map<NodeId, List<NodeId>> dependents = new LinkedHashMap<>();
        nodesById.keySet().forEach(nodeId -> dependents.put(nodeId, new ArrayList<>()));
        dependenciesByNode.forEach((nodeId, dependencies) -> {
            for (NodeId dependency : dependencies) {
                List<NodeId> dependentNodes = dependents.get(dependency);
                if (dependentNodes != null) {
                    dependentNodes.add(nodeId);
                }
            }
        });
        return dependents;
    }

    private static List<NodeDefinition> dagOrderOrOriginal(
            List<NodeDefinition> nodes,
            Map<NodeId, List<NodeId>> dependenciesByNode,
            Map<NodeId, List<NodeId>> dependentsByNode) {

        Map<NodeId, Integer> remainingDependencies = new LinkedHashMap<>();
        ArrayDeque<NodeId> ready = new ArrayDeque<>();
        for (NodeDefinition node : nodes) {
            int dependencyCount = dependenciesByNode.getOrDefault(node.id(), List.of()).size();
            remainingDependencies.put(node.id(), dependencyCount);
            if (dependencyCount == 0) {
                ready.add(node.id());
            }
        }

        List<NodeId> orderedIds = new ArrayList<>();
        while (!ready.isEmpty()) {
            NodeId current = ready.removeFirst();
            orderedIds.add(current);
            for (NodeId dependent : dependentsByNode.getOrDefault(current, List.of())) {
                int updated = remainingDependencies.merge(dependent, -1, Integer::sum);
                if (updated == 0) {
                    ready.add(dependent);
                }
            }
        }

        if (orderedIds.size() != nodes.size()) {
            return nodes;
        }

        Map<NodeId, NodeDefinition> nodesById = indexNodes(nodes);
        return orderedIds.stream()
                .map(nodesById::get)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private static List<NodeId> startNodeIds(List<NodeDefinition> nodes) {
        return nodes.stream()
                .filter(node -> node != null && dependenciesOf(node).isEmpty())
                .map(NodeDefinition::id)
                .toList();
    }

    private static List<NodeId> dependenciesOf(NodeDefinition node) {
        return node.dependsOn() != null ? node.dependsOn() : List.of();
    }

    private static <K, V> Map<K, V> immutableMap(Map<K, V> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    private static <K, V> Map<K, List<V>> immutableListMap(Map<K, List<V>> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<K, List<V>> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(key, value != null ? List.copyOf(value) : List.of()));
        return Collections.unmodifiableMap(copy);
    }
}
