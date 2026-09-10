package tech.kayys.gamelan.engine.execution;

import java.util.List;
import java.util.Map;

import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.payload.ExecutionPayloads;

/**
 * Execution plan result
 */
public record ExecutionPlan(
                List<NodeId> readyNodes,
                boolean isComplete,
                boolean isStuck,
                Map<String, Object> outputs) {
        public ExecutionPlan {
                readyNodes = ExecutionPayloads.immutableListCopy(readyNodes);
                outputs = ExecutionPayloads.immutableMap(outputs);
        }
}
