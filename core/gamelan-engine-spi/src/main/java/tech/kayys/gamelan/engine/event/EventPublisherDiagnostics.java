package tech.kayys.gamelan.engine.event;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Operational snapshot for an event publisher implementation.
 */
public record EventPublisherDiagnostics(
        String implementation,
        boolean available,
        Map<String, Long> counters,
        String error,
        String lastFailure,
        Instant lastFailureAt,
        Instant observedAt) {

    public EventPublisherDiagnostics {
        implementation = requireText(implementation, "implementation");
        counters = immutableCounters(counters);
        observedAt = observedAt != null ? observedAt : Instant.now();
    }

    public static EventPublisherDiagnostics available(
            String implementation,
            Map<String, Long> counters,
            String lastFailure,
            Instant lastFailureAt) {
        return new EventPublisherDiagnostics(
                implementation,
                true,
                counters,
                null,
                lastFailure,
                lastFailureAt,
                Instant.now());
    }

    public static EventPublisherDiagnostics unavailable(String implementation) {
        return unavailable(implementation, "diagnostics-not-implemented");
    }

    public static EventPublisherDiagnostics unavailable(String implementation, String error) {
        return new EventPublisherDiagnostics(
                implementation,
                false,
                Map.of(),
                error != null && !error.isBlank() ? error.trim() : "diagnostics-unavailable",
                null,
                null,
                Instant.now());
    }

    public long counter(String name) {
        return counters.getOrDefault(name, 0L);
    }

    private static Map<String, Long> immutableCounters(Map<String, Long> counters) {
        if (counters == null || counters.isEmpty()) {
            return Map.of();
        }
        Map<String, Long> normalized = new LinkedHashMap<>();
        counters.forEach((key, value) -> {
            if (key != null && !key.isBlank()) {
                normalized.put(key.trim(), Math.max(0L, value != null ? value : 0L));
            }
        });
        return Collections.unmodifiableMap(normalized);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " cannot be null");
        String normalized = value.trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(name + " cannot be blank");
        }
        return normalized;
    }
}
