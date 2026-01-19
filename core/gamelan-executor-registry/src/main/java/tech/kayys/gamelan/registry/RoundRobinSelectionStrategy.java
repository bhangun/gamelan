package tech.kayys.gamelan.registry;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import tech.kayys.gamelan.engine.executor.ExecutorInfo;
import tech.kayys.gamelan.engine.node.NodeId;

/**
 * Round-robin executor selection strategy
 */
public class RoundRobinSelectionStrategy implements ExecutorSelectionStrategy {
    
    private final Map<String, Integer> selectionIndex = new ConcurrentHashMap<>();
    
    @Override
    public Optional<ExecutorInfo> select(NodeId nodeId, List<ExecutorInfo> availableExecutors, Map<String, Object> context) {
        if (availableExecutors.isEmpty()) {
            return Optional.empty();
        }
        
        String nodeKey = nodeId.value();
        int currentIndex = selectionIndex.getOrDefault(nodeKey, 0);
        int nextIndex = (currentIndex + 1) % availableExecutors.size();
        selectionIndex.put(nodeKey, nextIndex);
        
        ExecutorInfo selected = availableExecutors.get(currentIndex % availableExecutors.size());
        return Optional.of(selected);
    }
    
    @Override
    public String getName() {
        return "round-robin";
    }
}