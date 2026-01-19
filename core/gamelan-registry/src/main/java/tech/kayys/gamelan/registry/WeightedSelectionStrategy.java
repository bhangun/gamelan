package tech.kayys.gamelan.registry;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import tech.kayys.gamelan.engine.executor.ExecutorInfo;
import tech.kayys.gamelan.engine.node.NodeId;

/**
 * Weighted executor selection strategy based on metadata
 */
public class WeightedSelectionStrategy implements ExecutorSelectionStrategy {
    
    private final Map<String, Integer> taskCounts = new ConcurrentHashMap<>();
    
    @Override
    public Optional<ExecutorInfo> select(NodeId nodeId, List<ExecutorInfo> availableExecutors, Map<String, Object> context) {
        if (availableExecutors.isEmpty()) {
            return Optional.empty();
        }
        
        // Find executor with lowest task count (simple load balancing)
        return availableExecutors.stream()
            .min((e1, e2) -> {
                int count1 = taskCounts.getOrDefault(e1.executorId(), 0);
                int count2 = taskCounts.getOrDefault(e2.executorId(), 0);
                return Integer.compare(count1, count2);
            })
            .map(executor -> {
                // Increment task count for selected executor
                taskCounts.merge(executor.executorId(), 1, Integer::sum);
                return executor;
            });
    }
    
    @Override
    public String getName() {
        return "weighted";
    }
    
    /**
     * Decrement task count when a task is completed
     */
    public void decrementTaskCount(String executorId) {
        taskCounts.computeIfPresent(executorId, (id, count) -> Math.max(0, count - 1));
    }
}