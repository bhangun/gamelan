package tech.kayys.silat.execution;

import java.util.List;
import java.util.Map;

import tech.kayys.silat.model.NodeId;

/**
 * Execution plan result
 */
public record ExecutionPlan(
        List<NodeId> readyNodes,
        boolean isComplete,
        boolean isStuck,
        Map<String, Object> outputs) {
}
