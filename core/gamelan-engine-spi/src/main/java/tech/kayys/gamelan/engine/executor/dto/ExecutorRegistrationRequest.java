package tech.kayys.gamelan.engine.executor.dto;

import java.util.Map;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import jakarta.validation.constraints.NotBlank;

/**
 * Executor registration request
 */
@Schema(description = "Executor registration request")
public record ExecutorRegistrationRequest(
        @NotBlank @Schema(description = "Executor ID", required = true) String executorId,

        @NotBlank @Schema(description = "Executor type", required = true) String executorType,

        @NotBlank @Schema(description = "Communication type", required = true) String communicationType,

        @NotBlank @Schema(description = "Endpoint", required = true) String endpoint,

        @Schema(description = "Metadata") Map<String, String> metadata) {
    public ExecutorRegistrationRequest {
        metadata = metadata != null ? metadata : Map.of();
    }
}
