package tech.kayys.gamelan.runtime.resource;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Coarse operational health derived from runtime capabilities.
 */
public record RuntimeCapabilityHealth(
        Status status,
        boolean ready,
        List<Issue> issues,
        Instant observedAt) {

    public RuntimeCapabilityHealth {
        status = status != null ? status : Status.READY;
        ready = status != Status.UNAVAILABLE;
        issues = issues == null ? List.of() : List.copyOf(issues);
        observedAt = observedAt != null ? observedAt : Instant.now();
    }

    public static RuntimeCapabilityHealth fromIssues(List<Issue> issues, Instant observedAt) {
        List<Issue> safeIssues = issues == null ? List.of() : List.copyOf(issues);
        Status status = safeIssues.stream().anyMatch(Issue::error)
                ? Status.UNAVAILABLE
                : safeIssues.stream().anyMatch(Issue::warning)
                        ? Status.DEGRADED
                        : Status.READY;
        return new RuntimeCapabilityHealth(status, status != Status.UNAVAILABLE, safeIssues, observedAt);
    }

    public enum Status {
        READY,
        DEGRADED,
        UNAVAILABLE
    }

    public enum Severity {
        INFO,
        WARN,
        ERROR
    }

    public record Issue(
            String code,
            Severity severity,
            String component,
            String implementation,
            String message) {

        public Issue {
            code = requireText(code, "code");
            severity = severity != null ? severity : Severity.INFO;
            component = blankToNull(component);
            implementation = blankToNull(implementation);
            message = requireText(message, "message");
        }

        private boolean error() {
            return severity == Severity.ERROR;
        }

        private boolean warning() {
            return severity == Severity.WARN;
        }
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
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
