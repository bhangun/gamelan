package tech.kayys.silat.registry;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import tech.kayys.silat.model.ExecutorInfo;
import tech.kayys.silat.model.NodeId;

/**
 * Strategy for selecting executors based on various criteria
 */
public interface ExecutorSelectionStrategy {

    /**
     * Select the best executor for a given node from the available executors
     */
    Optional<ExecutorInfo> select(NodeId nodeId, List<ExecutorInfo> availableExecutors, Map<String, Object> context);

    /**
     * Get the name of this strategy
     */
    String getName();
}