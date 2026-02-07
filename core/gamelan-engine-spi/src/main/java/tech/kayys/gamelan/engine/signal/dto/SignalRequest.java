package tech.kayys.gamelan.engine.signal.dto;

import java.util.Map;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import jakarta.validation.constraints.NotBlank;

/**
 * Request to send a signal to a workflow
 */
@Schema(description = "Request to signal a workflow")
public record SignalRequest(
        @NotBlank @Schema(description = "Signal name", required = true, example = "approval_received") String signalName,

        @NotBlank @Schema(description = "Target node ID", required = true, example = "approval-node") String targetNodeId,

        @Schema(description = "Signal payload") Map<String, Object> payload) {
    public SignalRequest {
        payload = payload != null ? payload : Map.of();
    }
}
