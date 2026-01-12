package tech.kayys.silat.model;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Execution Token - Security token for node execution
 * Ensures only authorized executors can report results
 */
public record ExecutionToken(
        String token,
        WorkflowRunId runId,
        NodeId nodeId,
        int attempt,
        Instant expiresAt) {
    public ExecutionToken {
        Objects.requireNonNull(token, "Token value cannot be null");
        Objects.requireNonNull(runId, "RunId cannot be null");
        Objects.requireNonNull(nodeId, "NodeId cannot be null");
        Objects.requireNonNull(expiresAt, "ExpiresAt cannot be null");
    }

    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    public boolean isValid() {
        return !isExpired();
    }

    public static ExecutionToken create(WorkflowRunId runId, NodeId nodeId, int attempt, Duration validity) {
        return new ExecutionToken(
                UUID.randomUUID().toString(),
                runId,
                nodeId,
                attempt,
                Instant.now().plus(validity));
    }

    // Keep value() for backward compatibility if needed, but token() is the primary
    // name now
    public String value() {
        return token;
    }
}