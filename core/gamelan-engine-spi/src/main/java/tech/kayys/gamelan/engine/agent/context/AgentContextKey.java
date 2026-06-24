package tech.kayys.gamelan.engine.agent.context;

import java.util.Objects;

import tech.kayys.gamelan.engine.tenant.TenantId;

/**
 * Stable identity for a persisted agent-context text document.
 */
public record AgentContextKey(
        TenantId tenantId,
        String workspaceId,
        String scope,
        String path) {

    private static final String[] RESERVED_FILE_SUFFIXES = {
            ".lock",
            ".meta.properties",
            ".tmp"
    };

    public AgentContextKey {
        Objects.requireNonNull(tenantId, "TenantId cannot be null");
        requireSegment(tenantId.value(), "tenantId");
        workspaceId = requireSegment(workspaceId, "workspaceId");
        scope = requireSegment(scope, "scope");
        path = requirePath(path);
    }

    private static String requireSegment(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " cannot be null or blank");
        }
        if (".".equals(value) || value.contains("/") || value.contains("\\") || value.contains("..")) {
            throw new IllegalArgumentException(field + " must be a single safe path segment");
        }
        return value;
    }

    private static String requirePath(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("path cannot be null or blank");
        }
        String normalized = value.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.endsWith("/")) {
            throw new IllegalArgumentException("path must be relative and cannot contain traversal segments");
        }
        String[] segments = normalized.split("/", -1);
        for (String segment : segments) {
            if (segment.isBlank() || ".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException("path must be relative and cannot contain traversal segments");
            }
        }
        for (String suffix : RESERVED_FILE_SUFFIXES) {
            if (normalized.endsWith(suffix)) {
                throw new IllegalArgumentException("path cannot use reserved local persistence suffix: " + suffix);
            }
        }
        return normalized;
    }
}
