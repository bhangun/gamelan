package tech.kayys.silat.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import jakarta.validation.constraints.NotBlank;

/**
 * Request to suspend a workflow run
 */
@Schema(description = "Request to suspend a workflow run")
public record SuspendRunRequest(
        @NotBlank @Schema(description = "Reason for suspension", required = true, example = "Waiting for approval") String reason,

        @Schema(description = "Node ID that is waiting", example = "approval-node") String waitingOnNodeId) {
}
