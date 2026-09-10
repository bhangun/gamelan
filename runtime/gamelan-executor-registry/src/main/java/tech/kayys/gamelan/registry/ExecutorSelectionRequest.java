package tech.kayys.gamelan.registry;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

import tech.kayys.gamelan.engine.executor.ExecutorCapabilityRequirements;
import tech.kayys.gamelan.engine.executor.ExecutorPlacementRequirements;
import tech.kayys.gamelan.engine.executor.ExecutorResourceRequirements;
import tech.kayys.gamelan.engine.executor.ExecutorSelectionPolicy;
import tech.kayys.gamelan.engine.node.NodeId;

/**
 * Immutable request envelope for executor selection.
 */
public record ExecutorSelectionRequest(
        NodeId nodeId,
        String executorType,
        ExecutorPlacementRequirements placement,
        boolean requireHealthy,
        String selectionStrategy,
        ExecutorCapabilityRequirements capabilityRequirements,
        ExecutorResourceRequirements resourceRequirements,
        Map<String, Object> selectionContext) {

    public static final String SELECTION_STRATEGY_KEY = ExecutorSelectionPolicy.CONTEXT_SELECTION_STRATEGY_KEY;
    public static final String STRATEGY_KEY = ExecutorSelectionPolicy.CONTEXT_STRATEGY_KEY;
    public static final String REQUIRED_CAPABILITIES_KEY = ExecutorSelectionPolicy.CONTEXT_REQUIRED_CAPABILITIES_KEY;
    public static final String PREFERRED_CAPABILITIES_KEY = ExecutorSelectionPolicy.CONTEXT_PREFERRED_CAPABILITIES_KEY;
    public static final String EXCLUDED_CAPABILITIES_KEY = ExecutorSelectionPolicy.CONTEXT_EXCLUDED_CAPABILITIES_KEY;
    public static final String CAPABILITIES_KEY = ExecutorSelectionPolicy.CONTEXT_CAPABILITIES_KEY;
    public static final String MIN_MEMORY_MB_KEY = ExecutorSelectionPolicy.CONTEXT_MIN_MEMORY_MB_KEY;
    public static final String MIN_CPU_CORES_KEY = ExecutorSelectionPolicy.CONTEXT_MIN_CPU_CORES_KEY;
    public static final String REGIONS_KEY = ExecutorSelectionPolicy.CONTEXT_REGIONS_KEY;
    public static final String DATA_RESIDENCIES_KEY = ExecutorSelectionPolicy.CONTEXT_DATA_RESIDENCIES_KEY;
    public static final String DATA_RESIDENCY_KEY = ExecutorSelectionPolicy.CONTEXT_DATA_RESIDENCY_KEY;

    public ExecutorSelectionRequest {
        Objects.requireNonNull(nodeId, "nodeId cannot be null");
        executorType = normalize(executorType);
        placement = placement != null ? placement : ExecutorPlacementRequirements.none();
        selectionContext = immutableContext(selectionContext);
        selectionStrategy = normalize(selectionStrategy);
        capabilityRequirements = capabilityRequirements != null
                ? capabilityRequirements
                : ExecutorCapabilityRequirements.none();
        resourceRequirements = resourceRequirements != null
                ? resourceRequirements
                : ExecutorResourceRequirements.none();
        if (selectionStrategy == null) {
            selectionStrategy = normalize(strategyValue(selectionContext));
        }
        if (capabilityRequirements.isEmpty()) {
            capabilityRequirements = ExecutorCapabilityRequirements.fromContext(selectionContext);
        }
        if (resourceRequirements.isEmpty()) {
            resourceRequirements = ExecutorResourceRequirements.fromContext(selectionContext);
        }
    }

    public ExecutorSelectionRequest(
            NodeId nodeId,
            String executorType,
            ExecutorPlacementRequirements placement,
            boolean requireHealthy,
            String selectionStrategy,
            ExecutorCapabilityRequirements capabilityRequirements,
            Map<String, Object> selectionContext) {
        this(
                nodeId,
                executorType,
                placement,
                requireHealthy,
                selectionStrategy,
                capabilityRequirements,
                ExecutorResourceRequirements.none(),
                selectionContext);
    }

    public ExecutorSelectionRequest(
            NodeId nodeId,
            String executorType,
            ExecutorPlacementRequirements placement,
            boolean requireHealthy,
            String selectionStrategy,
            Set<String> requiredCapabilities,
            Map<String, Object> selectionContext) {
        this(
                nodeId,
                executorType,
                placement,
                requireHealthy,
                selectionStrategy,
                ExecutorCapabilityRequirements.required(requiredCapabilities),
                ExecutorResourceRequirements.none(),
                selectionContext);
    }

    public ExecutorSelectionRequest(
            NodeId nodeId,
            String executorType,
            ExecutorPlacementRequirements placement,
            boolean requireHealthy,
            String selectionStrategy,
            Map<String, Object> selectionContext) {
        this(nodeId, executorType, placement, requireHealthy, selectionStrategy, Set.of(), selectionContext);
    }

    public ExecutorSelectionRequest(
            NodeId nodeId,
            String executorType,
            ExecutorPlacementRequirements placement,
            boolean requireHealthy,
            Map<String, Object> selectionContext) {
        this(nodeId, executorType, placement, requireHealthy, null, Set.of(), selectionContext);
    }

    public static ExecutorSelectionRequest forNode(
            NodeId nodeId,
            ExecutorPlacementRequirements placement) {
        return new ExecutorSelectionRequest(nodeId, null, placement, true, Map.of());
    }

    public static ExecutorSelectionRequest forNodeType(
            NodeId nodeId,
            String executorType,
            ExecutorPlacementRequirements placement) {
        return new ExecutorSelectionRequest(nodeId, executorType, placement, true, Map.of());
    }

    public static ExecutorSelectionRequest forNode(
            NodeId nodeId,
            ExecutorPlacementRequirements placement,
            Map<String, Object> selectionContext) {
        return new ExecutorSelectionRequest(nodeId, null, placement, true, selectionContext);
    }

    public static ExecutorSelectionRequest forNodeType(
            NodeId nodeId,
            String executorType,
            ExecutorPlacementRequirements placement,
            Map<String, Object> selectionContext) {
        return new ExecutorSelectionRequest(nodeId, executorType, placement, true, selectionContext);
    }

    public boolean hasExecutorType() {
        return executorType != null && !executorType.isBlank();
    }

    public boolean hasSelectionStrategy() {
        return selectionStrategy != null && !selectionStrategy.isBlank();
    }

    public ExecutorSelectionRequest withSelectionStrategy(String strategy) {
        return new ExecutorSelectionRequest(
                nodeId,
                executorType,
                placement,
                requireHealthy,
                strategy,
                capabilityRequirements,
                resourceRequirements,
                selectionContext);
    }

    public boolean hasRequiredCapabilities() {
        return capabilityRequirements.hasRequiredCapabilities();
    }

    public boolean hasPreferredCapabilities() {
        return capabilityRequirements.hasPreferredCapabilities();
    }

    public boolean hasExcludedCapabilities() {
        return capabilityRequirements.hasExcludedCapabilities();
    }

    public boolean hasResourceRequirements() {
        return !resourceRequirements.isEmpty();
    }

    public Set<String> requiredCapabilities() {
        return capabilityRequirements.requiredCapabilities();
    }

    public Set<String> preferredCapabilities() {
        return capabilityRequirements.preferredCapabilities();
    }

    public Set<String> excludedCapabilities() {
        return capabilityRequirements.excludedCapabilities();
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static Map<String, Object> immutableContext(Map<String, Object> context) {
        if (context == null || context.isEmpty()) {
            return Map.of();
        }
        return Map.copyOf(context);
    }

    private static String strategyValue(Map<String, Object> context) {
        if (context == null || context.isEmpty()) {
            return null;
        }
        Object value = context.get(SELECTION_STRATEGY_KEY);
        if (value == null) {
            value = context.get(STRATEGY_KEY);
        }
        return value instanceof String strategy ? strategy : null;
    }
}
