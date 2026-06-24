package tech.kayys.gamelan.engine.executor;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Capability constraints used to route work to compatible executors.
 */
public record ExecutorCapabilityRequirements(
        Set<String> requiredCapabilities,
        Set<String> preferredCapabilities,
        Set<String> excludedCapabilities) {

    public static final String REQUIRED_CAPABILITIES_KEY = "requiredCapabilities";
    public static final String PREFERRED_CAPABILITIES_KEY = "preferredCapabilities";
    public static final String EXCLUDED_CAPABILITIES_KEY = "excludedCapabilities";
    public static final String CAPABILITIES_KEY = "capabilities";
    public static final String METADATA_CAPABILITIES_KEY = "gamelan.executor.capabilities";

    public ExecutorCapabilityRequirements {
        requiredCapabilities = immutableCapabilities(requiredCapabilities);
        preferredCapabilities = immutableCapabilities(preferredCapabilities);
        excludedCapabilities = immutableCapabilities(excludedCapabilities);
    }

    public static ExecutorCapabilityRequirements none() {
        return new ExecutorCapabilityRequirements(Set.of(), Set.of(), Set.of());
    }

    public static ExecutorCapabilityRequirements required(Set<String> requiredCapabilities) {
        return new ExecutorCapabilityRequirements(requiredCapabilities, Set.of(), Set.of());
    }

    public static ExecutorCapabilityRequirements fromContext(Map<String, Object> context) {
        if (context == null || context.isEmpty()) {
            return none();
        }

        Object required = context.get(REQUIRED_CAPABILITIES_KEY);
        if (required == null) {
            required = context.get(CAPABILITIES_KEY);
        }

        return new ExecutorCapabilityRequirements(
                parseCapabilityValue(required),
                parseCapabilityValue(context.get(PREFERRED_CAPABILITIES_KEY)),
                parseCapabilityValue(context.get(EXCLUDED_CAPABILITIES_KEY)));
    }

    public boolean hasRequiredCapabilities() {
        return !requiredCapabilities.isEmpty();
    }

    public boolean hasPreferredCapabilities() {
        return !preferredCapabilities.isEmpty();
    }

    public boolean hasExcludedCapabilities() {
        return !excludedCapabilities.isEmpty();
    }

    public boolean hasHardConstraints() {
        return hasRequiredCapabilities() || hasExcludedCapabilities();
    }

    public boolean isEmpty() {
        return !hasRequiredCapabilities() && !hasPreferredCapabilities() && !hasExcludedCapabilities();
    }

    public CapabilityMatch evaluate(ExecutorInfo executor) {
        Set<String> supported = supportedCapabilities(executor);
        LinkedHashSet<String> missingRequired = new LinkedHashSet<>(requiredCapabilities);
        missingRequired.removeAll(supported);

        LinkedHashSet<String> matchedExcluded = new LinkedHashSet<>(excludedCapabilities);
        matchedExcluded.retainAll(supported);

        return new CapabilityMatch(
                missingRequired.isEmpty() && matchedExcluded.isEmpty(),
                Set.copyOf(missingRequired),
                Set.copyOf(matchedExcluded));
    }

    public boolean hardMatches(ExecutorInfo executor) {
        return evaluate(executor).matched();
    }

    public boolean preferredBy(ExecutorInfo executor) {
        if (!hasPreferredCapabilities()) {
            return false;
        }
        return supportedCapabilities(executor).containsAll(preferredCapabilities);
    }

    public Map<String, Object> toContextMap() {
        if (isEmpty()) {
            return Map.of();
        }

        Map<String, Object> context = new LinkedHashMap<>();
        if (hasRequiredCapabilities()) {
            context.put(REQUIRED_CAPABILITIES_KEY, sortedList(requiredCapabilities));
        }
        if (hasPreferredCapabilities()) {
            context.put(PREFERRED_CAPABILITIES_KEY, sortedList(preferredCapabilities));
        }
        if (hasExcludedCapabilities()) {
            context.put(EXCLUDED_CAPABILITIES_KEY, sortedList(excludedCapabilities));
        }
        return Map.copyOf(context);
    }

    public static Set<String> supportedCapabilities(ExecutorInfo executor) {
        if (executor == null || executor.metadata() == null) {
            return Set.of();
        }
        return parseCapabilities(executor.metadata().get(METADATA_CAPABILITIES_KEY));
    }

    public static Set<String> parseCapabilities(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }

        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (String token : raw.trim().split("[,;\\s]+")) {
            String normalized = normalizeCapability(token);
            if (normalized != null) {
                values.add(normalized);
            }
        }
        return Set.copyOf(values);
    }

    public static Set<String> parseCapabilityValue(Object source) {
        if (source == null) {
            return Set.of();
        }
        if (source instanceof String raw) {
            return parseCapabilities(raw);
        }
        if (!(source instanceof Iterable<?> values)) {
            return Set.of();
        }

        LinkedHashSet<String> capabilities = new LinkedHashSet<>();
        for (Object value : values) {
            String normalized = normalizeCapability(String.valueOf(value));
            if (normalized != null) {
                capabilities.add(normalized);
            }
        }
        return Set.copyOf(capabilities);
    }

    public static String normalizeCapability(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static Set<String> immutableCapabilities(Set<String> capabilities) {
        if (capabilities == null || capabilities.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String capability : capabilities) {
            String normalizedCapability = normalizeCapability(capability);
            if (normalizedCapability != null) {
                normalized.add(normalizedCapability);
            }
        }
        return Set.copyOf(normalized);
    }

    private static java.util.List<String> sortedList(Set<String> capabilities) {
        return capabilities.stream().sorted().toList();
    }

    public record CapabilityMatch(
            boolean matched,
            Set<String> missingRequiredCapabilities,
            Set<String> matchedExcludedCapabilities) {

        public CapabilityMatch {
            missingRequiredCapabilities = missingRequiredCapabilities == null
                    ? Set.of()
                    : Set.copyOf(missingRequiredCapabilities);
            matchedExcludedCapabilities = matchedExcludedCapabilities == null
                    ? Set.of()
                    : Set.copyOf(matchedExcludedCapabilities);
        }

        public String rejectionReason() {
            if (!missingRequiredCapabilities.isEmpty()) {
                return ExecutorSelectionRejectionReasons.REQUIRED_CAPABILITY_MISMATCH;
            }
            if (!matchedExcludedCapabilities.isEmpty()) {
                return ExecutorSelectionRejectionReasons.EXCLUDED_CAPABILITY_PRESENT;
            }
            return null;
        }
    }
}
