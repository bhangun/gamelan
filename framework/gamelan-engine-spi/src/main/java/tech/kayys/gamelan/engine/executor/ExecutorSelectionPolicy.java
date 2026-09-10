package tech.kayys.gamelan.engine.executor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import tech.kayys.gamelan.engine.error.ErrorCode;
import tech.kayys.gamelan.engine.error.GamelanException;
import tech.kayys.gamelan.engine.payload.ExecutionPayloads;

/**
 * Routing hints used by the engine when selecting an executor.
 */
public record ExecutorSelectionPolicy(
        String strategy,
        ExecutorCapabilityRequirements capabilityRequirements,
        ExecutorResourceRequirements resourceRequirements,
        Map<String, Object> context) {

    public static final String CONTEXT_KEY = "__executor_selection__";
    public static final String STRATEGY_KEY = "__executor_selection_strategy__";
    public static final String CONTEXT_SELECTION_STRATEGY_KEY = "selectionStrategy";
    public static final String CONTEXT_STRATEGY_KEY = "strategy";
    public static final String CONTEXT_REQUIRED_CAPABILITIES_KEY =
            ExecutorCapabilityRequirements.REQUIRED_CAPABILITIES_KEY;
    public static final String CONTEXT_PREFERRED_CAPABILITIES_KEY =
            ExecutorCapabilityRequirements.PREFERRED_CAPABILITIES_KEY;
    public static final String CONTEXT_EXCLUDED_CAPABILITIES_KEY =
            ExecutorCapabilityRequirements.EXCLUDED_CAPABILITIES_KEY;
    public static final String CONTEXT_CAPABILITIES_KEY = ExecutorCapabilityRequirements.CAPABILITIES_KEY;
    public static final String CONTEXT_MIN_MEMORY_MB_KEY = ExecutorResourceRequirements.MIN_MEMORY_MB_KEY;
    public static final String CONTEXT_MIN_CPU_CORES_KEY = ExecutorResourceRequirements.MIN_CPU_CORES_KEY;
    public static final String CONTEXT_REGIONS_KEY = ExecutorResourceRequirements.REGIONS_KEY;
    public static final String CONTEXT_DATA_RESIDENCIES_KEY = ExecutorResourceRequirements.DATA_RESIDENCIES_KEY;
    public static final String CONTEXT_DATA_RESIDENCY_KEY = ExecutorResourceRequirements.DATA_RESIDENCY_KEY;
    public static final String METADATA_CAPABILITIES_KEY =
            ExecutorCapabilityRequirements.METADATA_CAPABILITIES_KEY;
    public static final String METADATA_MEMORY_MB_KEY = ExecutorResourceRequirements.METADATA_MEMORY_MB_KEY;
    public static final String METADATA_CPU_CORES_KEY = ExecutorResourceRequirements.METADATA_CPU_CORES_KEY;
    public static final String METADATA_REGIONS_KEY = ExecutorResourceRequirements.METADATA_REGIONS_KEY;
    public static final String METADATA_DATA_RESIDENCIES_KEY =
            ExecutorResourceRequirements.METADATA_DATA_RESIDENCIES_KEY;

    public ExecutorSelectionPolicy {
        strategy = normalize(strategy);
        capabilityRequirements = capabilityRequirements != null
                ? capabilityRequirements
                : ExecutorCapabilityRequirements.none();
        resourceRequirements = resourceRequirements != null
                ? resourceRequirements
                : ExecutorResourceRequirements.none();
        context = ExecutionPayloads.immutableMap(context);
        if (strategy == null) {
            strategy = normalize(strategyValue(context));
        }
        if (capabilityRequirements.isEmpty()) {
            capabilityRequirements = ExecutorCapabilityRequirements.fromContext(context);
        }
        if (resourceRequirements.isEmpty()) {
            resourceRequirements = ExecutorResourceRequirements.fromContext(context);
        }
    }

    public ExecutorSelectionPolicy(
            String strategy,
            ExecutorCapabilityRequirements capabilityRequirements,
            Map<String, Object> context) {
        this(strategy, capabilityRequirements, ExecutorResourceRequirements.none(), context);
    }

    public ExecutorSelectionPolicy(
            String strategy,
            Set<String> requiredCapabilities,
            Map<String, Object> context) {
        this(
                strategy,
                ExecutorCapabilityRequirements.required(requiredCapabilities),
                ExecutorResourceRequirements.none(),
                context);
    }

    public ExecutorSelectionPolicy(String strategy, Map<String, Object> context) {
        this(strategy, ExecutorCapabilityRequirements.none(), ExecutorResourceRequirements.none(), context);
    }

    public static ExecutorSelectionPolicy none() {
        return new ExecutorSelectionPolicy(
                null,
                ExecutorCapabilityRequirements.none(),
                ExecutorResourceRequirements.none(),
                Map.of());
    }

    public static ExecutorSelectionPolicy fromContext(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return none();
        }

        Object rawSelection = source.get(CONTEXT_KEY);
        if (rawSelection != null
                && !(rawSelection instanceof Map<?, ?>)
                && !(rawSelection instanceof String)) {
            throw new GamelanException(
                    ErrorCode.VALIDATION_FAILED,
                    CONTEXT_KEY + " must be an object or strategy string");
        }

        Map<String, Object> selectionContext = rawSelection instanceof Map<?, ?> rawMap
                ? new LinkedHashMap<>(ExecutionPayloads.immutableStringKeyMap(rawMap))
                : new LinkedHashMap<>();

        String strategy = normalize(stringValue(source.get(STRATEGY_KEY)));
        if (strategy == null && rawSelection instanceof String strategyName) {
            strategy = normalize(strategyName);
        }

        return new ExecutorSelectionPolicy(
                strategy,
                ExecutorCapabilityRequirements.fromContext(selectionContext),
                ExecutorResourceRequirements.fromContext(selectionContext),
                selectionContext);
    }

    public boolean hasStrategy() {
        return strategy != null && !strategy.isBlank();
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

    public List<String> validationErrors() {
        List<String> errors = new ArrayList<>();
        Set<String> requiredExcluded = intersection(requiredCapabilities(), excludedCapabilities());
        if (!requiredExcluded.isEmpty()) {
            errors.add("required and excluded capabilities overlap: " + requiredExcluded.stream().sorted().toList());
        }
        Set<String> preferredExcluded = intersection(preferredCapabilities(), excludedCapabilities());
        if (!preferredExcluded.isEmpty()) {
            errors.add("preferred and excluded capabilities overlap: " + preferredExcluded.stream().sorted().toList());
        }
        return List.copyOf(errors);
    }

    public Map<String, Object> toSelectionContext() {
        if (!hasStrategy() && capabilityRequirements.isEmpty() && resourceRequirements.isEmpty()) {
            return context;
        }

        Map<String, Object> selectionContext = new LinkedHashMap<>(context);
        if (hasStrategy()) {
            selectionContext.put(CONTEXT_SELECTION_STRATEGY_KEY, strategy);
        }
        selectionContext.putAll(capabilityRequirements.toContextMap());
        selectionContext.putAll(resourceRequirements.toContextMap());
        return ExecutionPayloads.immutableMap(selectionContext);
    }

    public static Set<String> parseCapabilities(String raw) {
        return ExecutorCapabilityRequirements.parseCapabilities(raw);
    }

    public static String normalizeCapability(String value) {
        return ExecutorCapabilityRequirements.normalizeCapability(value);
    }

    private static String strategyValue(Map<String, Object> context) {
        if (context == null || context.isEmpty()) {
            return null;
        }
        Object value = context.get(CONTEXT_SELECTION_STRATEGY_KEY);
        if (value == null) {
            value = context.get(CONTEXT_STRATEGY_KEY);
        }
        return stringValue(value);
    }

    private static String stringValue(Object value) {
        return value instanceof String stringValue ? stringValue : null;
    }

    private static Set<String> intersection(Set<String> left, Set<String> right) {
        if (left.isEmpty() || right.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<String> values = new LinkedHashSet<>(left);
        values.retainAll(right);
        return Set.copyOf(values);
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
