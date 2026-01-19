package tech.kayys.gamelan.registry;

import java.util.List;
import java.util.Optional;

import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.engine.executor.ExecutorHealthInfo;
import tech.kayys.gamelan.engine.executor.ExecutorInfo;
import tech.kayys.gamelan.engine.node.NodeId;

/**
 * Enhanced Executor Registry Service Interface
 * Provides advanced capabilities for executor discovery and management
 */
public interface ExecutorRegistryService {

    /**
     * Get an executor for a specific node
     * Implements intelligent selection strategy based on load, health, and compatibility
     */
    Uni<Optional<ExecutorInfo>> getExecutorForNode(NodeId nodeId);

    /**
     * Get all executors (healthy and unhealthy)
     */
    Uni<List<ExecutorInfo>> getAllExecutors();

    /**
     * Get only healthy executors
     */
    Uni<List<ExecutorInfo>> getHealthyExecutors();

    /**
     * Register a new executor
     */
    Uni<Void> registerExecutor(ExecutorInfo executor);

    /**
     * Unregister an executor
     */
    Uni<Void> unregisterExecutor(String executorId);

    /**
     * Update executor heartbeat
     */
    Uni<Void> heartbeat(String executorId);

    /**
     * Get executor health information
     */
    Uni<Optional<ExecutorHealthInfo>> getHealthInfo(String executorId);

    /**
     * Check if an executor is healthy
     */
    Uni<Boolean> isHealthy(String executorId);

    /**
     * Get executor by ID
     */
    Uni<Optional<ExecutorInfo>> getExecutorById(String executorId);

    /**
     * Get executors by type
     */
    Uni<List<ExecutorInfo>> getExecutorsByType(String executorType);

    /**
     * Get executors by communication type
     */
    Uni<List<ExecutorInfo>> getExecutorsByCommunicationType(tech.kayys.gamelan.engine.protocol.CommunicationType communicationType);

    /**
     * Update executor metadata
     */
    Uni<Void> updateExecutorMetadata(String executorId, java.util.Map<String, String> metadata);

    /**
     * Get total registered executors count
     */
    Uni<Integer> getExecutorCount();

    /**
     * Get executor statistics
     */
    Uni<ExecutorStatistics> getStatistics();
}