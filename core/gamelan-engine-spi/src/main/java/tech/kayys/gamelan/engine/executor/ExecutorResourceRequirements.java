package tech.kayys.gamelan.engine.executor;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import tech.kayys.gamelan.engine.error.ErrorCode;
import tech.kayys.gamelan.engine.error.GamelanException;

/**
 * Resource and locality constraints used to route work to suitable executors.
 */
public record ExecutorResourceRequirements(
        Long minMemoryMb,
        Double minCpuCores,
        Set<String> regions,
        Set<String> dataResidencies) {

    public static final String MIN_MEMORY_MB_KEY = "minMemoryMb";
    public static final String MIN_CPU_CORES_KEY = "minCpuCores";
    public static final String REGIONS_KEY = "regions";
    public static final String DATA_RESIDENCIES_KEY = "dataResidencies";
    public static final String DATA_RESIDENCY_KEY = "dataResidency";

    public static final String METADATA_MEMORY_MB_KEY = "gamelan.executor.resources.memory-mb";
    public static final String METADATA_CPU_CORES_KEY = "gamelan.executor.resources.cpu-cores";
    public static final String METADATA_REGIONS_KEY = "gamelan.executor.resources.regions";
    public static final String METADATA_DATA_RESIDENCIES_KEY = "gamelan.executor.resources.data-residencies";

    public ExecutorResourceRequirements {
        minMemoryMb = normalizeLongRequirement(minMemoryMb, MIN_MEMORY_MB_KEY);
        minCpuCores = normalizeDoubleRequirement(minCpuCores, MIN_CPU_CORES_KEY);
        regions = immutableStringSet(regions);
        dataResidencies = immutableStringSet(dataResidencies);
    }

    public static ExecutorResourceRequirements none() {
        return new ExecutorResourceRequirements(null, null, Set.of(), Set.of());
    }

    public static ExecutorResourceRequirements fromContext(Map<String, Object> context) {
        if (context == null || context.isEmpty()) {
            return none();
        }

        Object dataResidencies = context.get(DATA_RESIDENCIES_KEY);
        if (dataResidencies == null) {
            dataResidencies = context.get(DATA_RESIDENCY_KEY);
        }

        return new ExecutorResourceRequirements(
                parseLongRequirement(context.get(MIN_MEMORY_MB_KEY), MIN_MEMORY_MB_KEY),
                parseDoubleRequirement(context.get(MIN_CPU_CORES_KEY), MIN_CPU_CORES_KEY),
                parseStringValue(context.get(REGIONS_KEY)),
                parseStringValue(dataResidencies));
    }

    public boolean hasMinMemoryMb() {
        return minMemoryMb != null;
    }

    public boolean hasMinCpuCores() {
        return minCpuCores != null;
    }

    public boolean hasRegions() {
        return !regions.isEmpty();
    }

    public boolean hasDataResidencies() {
        return !dataResidencies.isEmpty();
    }

    public boolean isEmpty() {
        return !hasMinMemoryMb() && !hasMinCpuCores() && !hasRegions() && !hasDataResidencies();
    }

    public ResourceMatch evaluate(ExecutorInfo executor) {
        if (executor == null) {
            return new ResourceMatch(
                    false,
                    Set.of("executor"),
                    Set.of(),
                    Set.of(),
                    Set.of());
        }

        Map<String, String> metadata = executor.metadata();
        LinkedHashSet<String> missingMetadata = new LinkedHashSet<>();
        LinkedHashSet<String> invalidMetadata = new LinkedHashSet<>();
        LinkedHashSet<String> insufficientResources = new LinkedHashSet<>();
        LinkedHashSet<String> mismatchedLocality = new LinkedHashSet<>();

        if (hasMinMemoryMb()) {
            Optional<Long> memoryMb = parseExecutorLongMetadata(
                    metadata,
                    METADATA_MEMORY_MB_KEY,
                    missingMetadata,
                    invalidMetadata);
            if (memoryMb.isPresent() && memoryMb.get() < minMemoryMb) {
                insufficientResources.add(METADATA_MEMORY_MB_KEY);
            }
        }

        if (hasMinCpuCores()) {
            Optional<Double> cpuCores = parseExecutorDoubleMetadata(
                    metadata,
                    METADATA_CPU_CORES_KEY,
                    missingMetadata,
                    invalidMetadata);
            if (cpuCores.isPresent() && cpuCores.get() < minCpuCores) {
                insufficientResources.add(METADATA_CPU_CORES_KEY);
            }
        }

        if (hasRegions()) {
            Set<String> supportedRegions = parseMetadataStringSet(metadata.get(METADATA_REGIONS_KEY));
            if (supportedRegions.isEmpty()) {
                missingMetadata.add(METADATA_REGIONS_KEY);
            } else if (!intersects(supportedRegions, regions)) {
                mismatchedLocality.add(METADATA_REGIONS_KEY);
            }
        }

        if (hasDataResidencies()) {
            Set<String> supportedResidencies = parseMetadataStringSet(metadata.get(METADATA_DATA_RESIDENCIES_KEY));
            if (supportedResidencies.isEmpty()) {
                missingMetadata.add(METADATA_DATA_RESIDENCIES_KEY);
            } else if (!intersects(supportedResidencies, dataResidencies)) {
                mismatchedLocality.add(METADATA_DATA_RESIDENCIES_KEY);
            }
        }

        boolean matched = missingMetadata.isEmpty()
                && invalidMetadata.isEmpty()
                && insufficientResources.isEmpty()
                && mismatchedLocality.isEmpty();
        return new ResourceMatch(
                matched,
                missingMetadata,
                invalidMetadata,
                insufficientResources,
                mismatchedLocality);
    }

    public boolean matches(ExecutorInfo executor) {
        return evaluate(executor).matched();
    }

    public Map<String, Object> toContextMap() {
        if (isEmpty()) {
            return Map.of();
        }

        Map<String, Object> context = new LinkedHashMap<>();
        if (hasMinMemoryMb()) {
            context.put(MIN_MEMORY_MB_KEY, minMemoryMb);
        }
        if (hasMinCpuCores()) {
            context.put(MIN_CPU_CORES_KEY, minCpuCores);
        }
        if (hasRegions()) {
            context.put(REGIONS_KEY, sortedList(regions));
        }
        if (hasDataResidencies()) {
            context.put(DATA_RESIDENCIES_KEY, sortedList(dataResidencies));
        }
        return Map.copyOf(context);
    }

    public static Set<String> parseStringValue(Object source) {
        if (source == null) {
            return Set.of();
        }
        if (source instanceof String raw) {
            return parseMetadataStringSet(raw);
        }
        if (!(source instanceof Iterable<?> values)) {
            return Set.of();
        }

        LinkedHashSet<String> parsed = new LinkedHashSet<>();
        for (Object value : values) {
            String normalized = normalizeString(String.valueOf(value));
            if (normalized != null) {
                parsed.add(normalized);
            }
        }
        return Set.copyOf(parsed);
    }

    public static Set<String> parseMetadataStringSet(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }

        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (String token : raw.trim().split("[,;\\s]+")) {
            String normalized = normalizeString(token);
            if (normalized != null) {
                values.add(normalized);
            }
        }
        return Set.copyOf(values);
    }

    private static Optional<Long> parseExecutorLongMetadata(
            Map<String, String> metadata,
            String key,
            Set<String> missingMetadata,
            Set<String> invalidMetadata) {
        String raw = metadata.get(key);
        if (raw == null || raw.isBlank()) {
            missingMetadata.add(key);
            return Optional.empty();
        }
        try {
            long parsed = Long.parseLong(raw.trim());
            if (parsed < 0) {
                invalidMetadata.add(key);
                return Optional.empty();
            }
            return Optional.of(parsed);
        } catch (NumberFormatException error) {
            invalidMetadata.add(key);
            return Optional.empty();
        }
    }

    private static Optional<Double> parseExecutorDoubleMetadata(
            Map<String, String> metadata,
            String key,
            Set<String> missingMetadata,
            Set<String> invalidMetadata) {
        String raw = metadata.get(key);
        if (raw == null || raw.isBlank()) {
            missingMetadata.add(key);
            return Optional.empty();
        }
        try {
            double parsed = Double.parseDouble(raw.trim());
            if (parsed < 0 || Double.isNaN(parsed) || Double.isInfinite(parsed)) {
                invalidMetadata.add(key);
                return Optional.empty();
            }
            return Optional.of(parsed);
        } catch (NumberFormatException error) {
            invalidMetadata.add(key);
            return Optional.empty();
        }
    }

    private static Long parseLongRequirement(Object value, String key) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return normalizeLongRequirement(number.longValue(), key);
        }
        if (value instanceof String raw && !raw.isBlank()) {
            try {
                return normalizeLongRequirement(Long.parseLong(raw.trim()), key);
            } catch (NumberFormatException error) {
                throw invalidRequirement(key, raw);
            }
        }
        throw invalidRequirement(key, value);
    }

    private static Double parseDoubleRequirement(Object value, String key) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return normalizeDoubleRequirement(number.doubleValue(), key);
        }
        if (value instanceof String raw && !raw.isBlank()) {
            try {
                return normalizeDoubleRequirement(Double.parseDouble(raw.trim()), key);
            } catch (NumberFormatException error) {
                throw invalidRequirement(key, raw);
            }
        }
        throw invalidRequirement(key, value);
    }

    private static Long normalizeLongRequirement(Long value, String key) {
        if (value == null) {
            return null;
        }
        if (value < 0) {
            throw invalidRequirement(key, value);
        }
        return value;
    }

    private static Double normalizeDoubleRequirement(Double value, String key) {
        if (value == null) {
            return null;
        }
        if (value < 0 || Double.isNaN(value) || Double.isInfinite(value)) {
            throw invalidRequirement(key, value);
        }
        return value;
    }

    private static Set<String> immutableStringSet(Set<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<String> copy = new LinkedHashSet<>();
        for (String value : values) {
            String normalized = normalizeString(value);
            if (normalized != null) {
                copy.add(normalized);
            }
        }
        return Set.copyOf(copy);
    }

    private static String normalizeString(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean intersects(Set<String> left, Set<String> right) {
        for (String value : left) {
            if (right.contains(value)) {
                return true;
            }
        }
        return false;
    }

    private static java.util.List<String> sortedList(Set<String> values) {
        return values.stream().sorted().toList();
    }

    private static GamelanException invalidRequirement(String key, Object value) {
        return new GamelanException(
                ErrorCode.VALIDATION_FAILED,
                "Invalid executor resource requirement " + key + ": " + value);
    }

    public record ResourceMatch(
            boolean matched,
            Set<String> missingMetadataKeys,
            Set<String> invalidMetadataKeys,
            Set<String> insufficientResourceKeys,
            Set<String> mismatchedLocalityKeys) {

        public ResourceMatch {
            missingMetadataKeys = missingMetadataKeys == null ? Set.of() : Set.copyOf(missingMetadataKeys);
            invalidMetadataKeys = invalidMetadataKeys == null ? Set.of() : Set.copyOf(invalidMetadataKeys);
            insufficientResourceKeys = insufficientResourceKeys == null
                    ? Set.of()
                    : Set.copyOf(insufficientResourceKeys);
            mismatchedLocalityKeys = mismatchedLocalityKeys == null ? Set.of() : Set.copyOf(mismatchedLocalityKeys);
        }
    }
}
