package tech.kayys.gamelan.dag;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import tech.kayys.gamelan.engine.node.NodeDefinition;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinition;

/**
 * Default DAG ordering using topological sort.
 */
public class DefaultDagSchedulerService implements DagSchedulerService {

    @Override
    public List<NodeId> orderReadyNodes(WorkflowDefinition definition, List<NodeId> readyNodes) {
        if (readyNodes == null || readyNodes.isEmpty()) {
            return List.of();
        }

        List<NodeId> topoOrder = topologicalOrder(definition);
        Map<NodeId, Integer> position = new HashMap<>();
        for (int i = 0; i < topoOrder.size(); i++) {
            position.put(topoOrder.get(i), i);
        }

        List<NodeId> sorted = new ArrayList<>(readyNodes);
        sorted.sort((a, b) -> Integer.compare(
                position.getOrDefault(a, Integer.MAX_VALUE),
                position.getOrDefault(b, Integer.MAX_VALUE)));
        return sorted;
    }

    private List<NodeId> topologicalOrder(WorkflowDefinition definition) {
        Map<NodeId, Integer> indegree = new HashMap<>();
        Map<NodeId, Set<NodeId>> adjacency = new HashMap<>();

        for (NodeDefinition node : definition.nodes()) {
            indegree.put(node.id(), 0);
        }
        for (NodeDefinition node : definition.nodes()) {
            for (NodeId dep : node.dependsOn()) {
                adjacency.computeIfAbsent(dep, k -> new java.util.HashSet<>()).add(node.id());
                indegree.merge(node.id(), 1, Integer::sum);
            }
        }

        ArrayDeque<NodeId> queue = new ArrayDeque<>();
        for (NodeDefinition node : definition.nodes()) {
            if (indegree.getOrDefault(node.id(), 0) == 0) {
                queue.add(node.id());
            }
        }

        List<NodeId> result = new ArrayList<>();
        while (!queue.isEmpty()) {
            NodeId current = queue.poll();
            result.add(current);
            for (NodeId child : adjacency.getOrDefault(current, Set.of())) {
                int next = indegree.merge(child, -1, Integer::sum);
                if (next == 0) {
                    queue.add(child);
                }
            }
        }
        return result;
    }
}
