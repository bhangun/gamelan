package tech.kayys.gamelan.engine.run.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import jakarta.validation.constraints.NotBlank;

/**
 * Request to cancel a workflow run
 */
@Schema(description = "Request to cancel a workflow run")
public record CancelRunRequest(
        @NotBlank @Schema(description = "Reason for cancellation", required = true, example = "User requested cancellation") String reason) {
}
