package tech.kayys.silat.model;

import java.time.Instant;

/**
 * Callback Registration
 */
public record CallbackRegistration(
        String callbackToken,
        WorkflowRunId runId,
        NodeId nodeId,
        String callbackUrl,
        Instant expiresAt) {
}
