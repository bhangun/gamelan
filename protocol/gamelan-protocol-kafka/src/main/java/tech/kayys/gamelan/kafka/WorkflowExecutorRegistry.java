package tech.kayys.gamelan.kafka;

import java.util.HashMap;
import java.util.Map;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import tech.kayys.gamelan.engine.node.NodeId;

/**
 * Workflow executor registry
 */
@ApplicationScoped
public class WorkflowExecutorRegistry {

    private final Map<String, WorkflowExecutor> executors = new HashMap<>();

    public void registerExecutor(String nodeType, WorkflowExecutor executor) {
        executors.put(nodeType, executor);
    }

    public Uni<WorkflowExecutor> getExecutor(NodeId nodeId) {
        // Find executor for node type
        // For now, return dummy
        return Uni.createFrom().nullItem();
    }
}
