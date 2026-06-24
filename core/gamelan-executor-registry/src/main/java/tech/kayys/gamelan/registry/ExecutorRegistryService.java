package tech.kayys.gamelan.registry;

import java.util.List;
import java.util.Optional;

import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.engine.error.GamelanException;
import tech.kayys.gamelan.engine.executor.ExecutorCapabilityRequirements;
import tech.kayys.gamelan.engine.executor.ExecutorHealthInfo;
import tech.kayys.gamelan.engine.executor.ExecutorInfo;
import tech.kayys.gamelan.engine.executor.ExecutorPlacementRequirements;
import tech.kayys.gamelan.engine.executor.ExecutorResourceRequirements;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.protocol.CommunicationType;

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
     * Get an executor for a specific node constrained by runtime placement needs.
     */
    default Uni<Optional<ExecutorInfo>> getExecutorForNode(
            NodeId nodeId,
            ExecutorPlacementRequirements placement) {
        ExecutorPlacementRequirements effectivePlacement = placement != null
                ? placement
                : ExecutorPlacementRequirements.none();
        return getExecutorForNode(nodeId)
                .map(candidate -> candidate.filter(executor -> matchesPlacement(executor, effectivePlacement)));
    }

    /**
     * Get all executors (healthy and unhealthy)
     */
    Uni<List<ExecutorInfo>> getAllExecutors();

    /**
     * Get only healthy executors
     */
    Uni<List<ExecutorInfo>> getHealthyExecutors();

    /**
     * Get only healthy executors constrained by runtime placement needs.
     */
    default Uni<List<ExecutorInfo>> getHealthyExecutors(ExecutorPlacementRequirements placement) {
        return getHealthyExecutors()
                .map(executors -> filterByPlacement(executors, placement));
    }

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
     * Update executor heartbeat with runtime load information.
     */
    default Uni<Void> heartbeat(String executorId, int currentTaskCount) {
        return heartbeat(executorId);
    }

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
     * Get executors by type constrained by runtime placement needs.
     */
    default Uni<List<ExecutorInfo>> getExecutorsByType(
            String executorType,
            ExecutorPlacementRequirements placement) {
        return getExecutorsByType(executorType)
                .map(executors -> filterByPlacement(executors, placement));
    }

    /**
     * Get healthy executors by type constrained by runtime placement needs.
     */
    default Uni<List<ExecutorInfo>> getHealthyExecutorsByType(
            String executorType,
            ExecutorPlacementRequirements placement) {
        return getHealthyExecutors(placement)
                .map(executors -> executors.stream()
                        .filter(executor -> java.util.Objects.equals(executor.executorType(), executorType))
                        .toList());
    }

    /**
     * Select a healthy executor for a node constrained by executor type and placement.
     */
    default Uni<Optional<ExecutorInfo>> getExecutorForNodeByType(
            NodeId nodeId,
            String executorType,
            ExecutorPlacementRequirements placement) {
        if (executorType == null || executorType.isBlank()) {
            return Uni.createFrom().item(Optional.empty());
        }
        return getHealthyExecutorsByType(executorType, placement)
                .map(executors -> selectDeterministically(nodeId, executors));
    }

    /**
     * Select an executor using a future-proof request envelope.
     */
    default Uni<Optional<ExecutorInfo>> selectExecutor(ExecutorSelectionRequest request) {
        ExecutorSelectionRequest effectiveRequest = java.util.Objects.requireNonNull(
                request,
                "request cannot be null");
        if (effectiveRequest.hasExecutorType()) {
            return getHealthyExecutorsByType(effectiveRequest.executorType(), effectiveRequest.placement())
                    .map(executors -> filterByCapabilities(executors, effectiveRequest.capabilityRequirements()))
                    .map(executors -> filterByResources(executors, effectiveRequest.resourceRequirements()))
                    .map(executors -> applyPreferredCapabilityBias(
                            executors,
                            effectiveRequest.capabilityRequirements()))
                    .map(executors -> selectDeterministically(effectiveRequest.nodeId(), executors));
        }
        return getHealthyExecutors(effectiveRequest.placement())
                .map(executors -> filterByCapabilities(executors, effectiveRequest.capabilityRequirements()))
                .map(executors -> filterByResources(executors, effectiveRequest.resourceRequirements()))
                .map(executors -> applyPreferredCapabilityBias(
                        executors,
                        effectiveRequest.capabilityRequirements()))
                .map(executors -> selectDeterministically(effectiveRequest.nodeId(), executors));
    }

    /**
     * Select an executor and return diagnostic counters explaining the decision.
     */
    default Uni<ExecutorSelectionReport> selectExecutorWithDiagnostics(ExecutorSelectionRequest request) {
        ExecutorSelectionRequest effectiveRequest = java.util.Objects.requireNonNull(
                request,
                "request cannot be null");
        return selectExecutor(effectiveRequest)
                .map(selected -> ExecutorSelectionReport.selectionOnly(effectiveRequest, selected));
    }

    /**
     * Get executors by communication type
     */
    Uni<List<ExecutorInfo>> getExecutorsByCommunicationType(CommunicationType communicationType);

    /**
     * Get executors by communication type constrained by runtime placement needs.
     */
    default Uni<List<ExecutorInfo>> getExecutorsByCommunicationType(
            CommunicationType communicationType,
            ExecutorPlacementRequirements placement) {
        return getExecutorsByCommunicationType(communicationType)
                .map(executors -> filterByPlacement(executors, placement));
    }

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

    private static List<ExecutorInfo> filterByPlacement(
            List<ExecutorInfo> executors,
            ExecutorPlacementRequirements placement) {
        if (executors == null || executors.isEmpty()) {
            return List.of();
        }
        ExecutorPlacementRequirements effectivePlacement = placement != null
                ? placement
                : ExecutorPlacementRequirements.none();
        return executors.stream()
                .filter(executor -> matchesPlacement(executor, effectivePlacement))
                .toList();
    }

    private static List<ExecutorInfo> filterByCapabilities(
            List<ExecutorInfo> executors,
            ExecutorCapabilityRequirements requirements) {
        if (executors == null || executors.isEmpty()) {
            return List.of();
        }
        ExecutorCapabilityRequirements effectiveRequirements = requirements != null
                ? requirements
                : ExecutorCapabilityRequirements.none();
        if (effectiveRequirements.isEmpty()) {
            return executors;
        }
        return executors.stream()
                .filter(effectiveRequirements::hardMatches)
                .toList();
    }

    private static List<ExecutorInfo> filterByResources(
            List<ExecutorInfo> executors,
            ExecutorResourceRequirements requirements) {
        if (executors == null || executors.isEmpty()) {
            return List.of();
        }
        ExecutorResourceRequirements effectiveRequirements = requirements != null
                ? requirements
                : ExecutorResourceRequirements.none();
        if (effectiveRequirements.isEmpty()) {
            return executors;
        }
        return executors.stream()
                .filter(effectiveRequirements::matches)
                .toList();
    }

    private static List<ExecutorInfo> applyPreferredCapabilityBias(
            List<ExecutorInfo> executors,
            ExecutorCapabilityRequirements requirements) {
        if (executors == null || executors.isEmpty()) {
            return List.of();
        }
        if (requirements == null || !requirements.hasPreferredCapabilities()) {
            return executors;
        }
        List<ExecutorInfo> preferred = executors.stream()
                .filter(requirements::preferredBy)
                .toList();
        return preferred.isEmpty() ? executors : preferred;
    }

    private static boolean matchesPlacement(
            ExecutorInfo executor,
            ExecutorPlacementRequirements placement) {
        try {
            return placement == null || placement.matches(executor);
        } catch (GamelanException ignored) {
            return false;
        }
    }

    private static Optional<ExecutorInfo> selectDeterministically(
            NodeId nodeId,
            List<ExecutorInfo> executors) {
        if (executors == null || executors.isEmpty()) {
            return Optional.empty();
        }
        String nodeKey = nodeId != null ? nodeId.value() : "";
        return Optional.of(executors.get(Math.floorMod(nodeKey.hashCode(), executors.size())));
    }
}
