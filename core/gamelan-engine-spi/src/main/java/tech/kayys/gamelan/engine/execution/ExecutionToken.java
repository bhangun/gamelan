package tech.kayys.gamelan.engine.execution;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

import tech.kayys.gamelan.engine.error.ErrorCode;
import tech.kayys.gamelan.engine.error.GamelanException;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;

/**
 * Execution Token - Security token for node execution
 * Ensures only authorized executors can report results
 */
public record ExecutionToken(
        String token,
        WorkflowRunId runId,
        TenantId tenantId,
        NodeId nodeId,
        int attempt,
        Instant expiresAt) {

    public ExecutionToken {
        Objects.requireNonNull(token, "Token value cannot be null");
        Objects.requireNonNull(runId, "RunId cannot be null");
        Objects.requireNonNull(nodeId, "NodeId cannot be null");
        Objects.requireNonNull(expiresAt, "ExpiresAt cannot be null");
        if (token.isBlank()) {
            throw new GamelanException(ErrorCode.VALIDATION_FAILED, "Token value cannot be blank");
        }
        if (attempt <= 0) {
            throw new GamelanException(ErrorCode.VALIDATION_FAILED, "ExecutionToken attempt must be positive");
        }
    }

    public ExecutionToken(String token, WorkflowRunId runId, NodeId nodeId, int attempt, Instant expiresAt) {
        this(token, runId, null, nodeId, attempt, expiresAt);
    }

    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    public boolean isValid() {
        return !isExpired();
    }

    public static ExecutionToken create(WorkflowRunId runId, NodeId nodeId, int attempt, Duration validity) {
        return create(runId, null, nodeId, attempt, validity);
    }

    public static ExecutionToken create(
            WorkflowRunId runId,
            TenantId tenantId,
            NodeId nodeId,
            int attempt,
            Duration validity) {
        Objects.requireNonNull(validity, "ExecutionToken validity cannot be null");
        if (validity.isZero() || validity.isNegative()) {
            throw new GamelanException(ErrorCode.VALIDATION_FAILED, "ExecutionToken validity must be positive");
        }

        return new ExecutionToken(
                BearerTokens.randomUrlSafe(),
                runId,
                tenantId,
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
