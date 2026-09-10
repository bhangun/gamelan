package tech.kayys.gamelan.dag;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Simple DAG scheduler using indegree tracking.
 * This helper is intended for DAG mode execution policies.
 */
public class DagScheduler {

    private final Map<String, Integer> indegree = new HashMap<>();
    private final Deque<String> ready = new ArrayDeque<>();

    public DagScheduler(Map<String, Set<String>> adjacency, List<String> nodes) {
        for (String nodeId : nodes) {
            indegree.put(nodeId, 0);
        }
        for (Map.Entry<String, Set<String>> entry : adjacency.entrySet()) {
            for (String to : entry.getValue()) {
                indegree.merge(to, 1, Integer::sum);
            }
        }
        for (Map.Entry<String, Integer> entry : indegree.entrySet()) {
            if (entry.getValue() == 0) {
                ready.add(entry.getKey());
            }
        }
    }

    public String nextReady() {
        return ready.poll();
    }

    public void onNodeCompleted(String nodeId, Map<String, Set<String>> adjacency) {
        for (String child : adjacency.getOrDefault(nodeId, Set.of())) {
            int remaining = indegree.merge(child, -1, Integer::sum);
            if (remaining == 0) {
                ready.add(child);
            }
        }
    }
}
