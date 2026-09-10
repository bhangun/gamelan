package tech.kayys.gamelan.engine.callback;

import java.time.Instant;
import java.util.Objects;

import tech.kayys.gamelan.engine.error.ErrorCode;
import tech.kayys.gamelan.engine.error.GamelanException;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;

/**
 * Callback Registration
 */
public record CallbackRegistration(
                String callbackToken,
                WorkflowRunId runId,
                TenantId tenantId,
                NodeId nodeId,
                String callbackUrl,
                Instant expiresAt) {

        public CallbackRegistration(
                        String callbackToken,
                        WorkflowRunId runId,
                        NodeId nodeId,
                        String callbackUrl,
                        Instant expiresAt) {
                this(callbackToken, runId, null, nodeId, callbackUrl, expiresAt);
        }

        public CallbackRegistration {
                Objects.requireNonNull(callbackToken, "Callback token cannot be null");
                Objects.requireNonNull(runId, "WorkflowRunId cannot be null");
                Objects.requireNonNull(nodeId, "NodeId cannot be null");
                Objects.requireNonNull(expiresAt, "Callback expiration cannot be null");
                if (callbackToken.isBlank()) {
                        throw new GamelanException(ErrorCode.VALIDATION_FAILED, "Callback token cannot be blank");
                }
                if (callbackUrl == null || callbackUrl.isBlank()) {
                        throw new GamelanException(ErrorCode.VALIDATION_FAILED, "Callback URL is required");
                }
        }
}
