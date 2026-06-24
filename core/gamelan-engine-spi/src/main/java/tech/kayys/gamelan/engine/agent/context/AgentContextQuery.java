package tech.kayys.gamelan.engine.agent.context;

import java.util.Objects;

import tech.kayys.gamelan.engine.tenant.TenantId;

/**
 * Query local/cloud agent context documents by tenant, workspace, and optional scope.
 */
public record AgentContextQuery(
        TenantId tenantId,
        String workspaceId,
        String scope,
        String pathPrefix,
        Integer maxResults,
        AgentContextCursor after) {

    private static final String[] RESERVED_FILE_SUFFIXES = {
            ".lock",
            ".meta.properties",
            ".tmp"
    };

    public AgentContextQuery(TenantId tenantId, String workspaceId, String scope) {
        this(tenantId, workspaceId, scope, null, null, null);
    }

    public AgentContextQuery(TenantId tenantId, String workspaceId, String scope, String pathPrefix) {
        this(tenantId, workspaceId, scope, pathPrefix, null, null);
    }

    public AgentContextQuery(
            TenantId tenantId,
            String workspaceId,
            String scope,
            String pathPrefix,
            Integer maxResults) {
        this(tenantId, workspaceId, scope, pathPrefix, maxResults, null);
    }

    public AgentContextQuery {
        Objects.requireNonNull(tenantId, "TenantId cannot be null");
        requireSegment(tenantId.value(), "tenantId");
        workspaceId = requireSegment(workspaceId, "workspaceId");
        scope = scope != null && !scope.isBlank() ? requireSegment(scope, "scope") : null;
        pathPrefix = pathPrefix != null && !pathPrefix.isBlank() ? requirePathPrefix(pathPrefix) : null;
        maxResults = maxResults != null ? requireMaxResults(maxResults) : null;
        if (after != null) {
            requireCursorMatchesScope(scope, after);
            requireCursorMatchesPathPrefix(pathPrefix, after);
        }
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

    private static String requirePathPrefix(String value) {
        String normalized = value.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.contains("//")) {
            throw new IllegalArgumentException("pathPrefix must be relative and cannot contain traversal segments");
        }
        String prefix = normalized.endsWith("/")
                ? normalized.substring(0, normalized.length() - 1)
                : normalized;
        if (prefix.isBlank()) {
            throw new IllegalArgumentException("pathPrefix cannot be blank");
        }
        for (String segment : prefix.split("/", -1)) {
            if (segment.isBlank() || ".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException("pathPrefix must be relative and cannot contain traversal segments");
            }
        }
        for (String suffix : RESERVED_FILE_SUFFIXES) {
            if (prefix.endsWith(suffix)) {
                throw new IllegalArgumentException("pathPrefix cannot use reserved local persistence suffix: " + suffix);
            }
        }
        return normalized.endsWith("/") ? prefix + "/" : prefix;
    }

    private static Integer requireMaxResults(Integer value) {
        if (value <= 0) {
            throw new IllegalArgumentException("maxResults must be greater than zero");
        }
        return value;
    }

    private static void requireCursorMatchesScope(String scope, AgentContextCursor after) {
        if (scope != null && !scope.equals(after.scope())) {
            throw new IllegalArgumentException("after cursor scope must match query scope");
        }
    }

    private static void requireCursorMatchesPathPrefix(String pathPrefix, AgentContextCursor after) {
        if (pathPrefix != null && !after.path().startsWith(pathPrefix)) {
            throw new IllegalArgumentException("after cursor path must match query pathPrefix");
        }
    }
}
