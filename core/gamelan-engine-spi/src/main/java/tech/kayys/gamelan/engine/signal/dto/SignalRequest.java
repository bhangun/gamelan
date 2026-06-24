package tech.kayys.gamelan.engine.signal.dto;

import java.util.Map;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import jakarta.validation.constraints.NotBlank;
import tech.kayys.gamelan.engine.payload.ExecutionPayloads;

/**
 * Request to send a signal to a workflow
 */
@Schema(description = "Request to signal a workflow")
public record SignalRequest(
        @NotBlank @Schema(description = "Signal name", required = true, example = "approval_received") String signalName,

        @Schema(description = "Target node ID", example = "approval-node") String targetNodeId,

        @Schema(description = "Signal payload") Map<String, Object> payload,

        @Schema(description = "Client-provided idempotency key for distinguishing retries from new signals")
        String idempotencyKey) {
    public SignalRequest {
        targetNodeId = targetNodeId != null && !targetNodeId.isBlank() ? targetNodeId.trim() : null;
        payload = ExecutionPayloads.immutableMap(payload);
        idempotencyKey = idempotencyKey != null && !idempotencyKey.isBlank() ? idempotencyKey.trim() : null;
    }
}
