package tech.kayys.silat.registry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import tech.kayys.silat.model.ExecutorInfo;
import tech.kayys.silat.model.NodeId;

/**
 * Executor Registry - Manages executor discovery
 */

public class ExecutorRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(ExecutorRegistry.class);

    // In-memory registry (could be backed by Consul, K8s, etc.)
    private final Map<String, ExecutorInfo> executors = new ConcurrentHashMap<>();

    public Uni<ExecutorInfo> getExecutorForNode(NodeId nodeId) {
        // Simple round-robin selection
        // In production, this would use service discovery
        List<ExecutorInfo> availableExecutors = new ArrayList<>(executors.values());

        if (availableExecutors.isEmpty()) {
            return Uni.createFrom().failure(
                    new IllegalStateException("No executors available"));
        }

        // For now, return first available
        return Uni.createFrom().item(availableExecutors.get(0));
    }

    public void registerExecutor(ExecutorInfo executor) {
        executors.put(executor.executorId(), executor);
        LOG.info("Registered executor: {} ({})",
                executor.executorId(), executor.communicationType());
    }

    public void unregisterExecutor(String executorId) {
        executors.remove(executorId);
        LOG.info("Unregistered executor: {}", executorId);
    }
    public void heartbeat(String executorId) {
        ExecutorInfo executor = executors.get(executorId);
        if (executor != null) {
            // Update last heartbeat timestamp
            LOG.debug("Heartbeat received from executor: {}", executorId);
        } else {
            LOG.warn("Heartbeat from unregistered executor: {}", executorId);
        }
    }

}
