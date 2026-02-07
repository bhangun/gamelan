package tech.kayys.gamelan.engine.callback;

import java.time.Instant;

import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;

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
