package tech.kayys.gamelan.engine.io.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import jakarta.validation.constraints.NotBlank;
import tech.kayys.gamelan.engine.payload.ExecutionPayloads;

/**
 * Input definition DTO
 */
@Schema(description = "Input definition")
public record InputDefinitionDto(
                @NotBlank @Schema(description = "Name", required = true) String name,

                @NotBlank @Schema(description = "Type", required = true) String type,

                @Schema(description = "Is required") boolean required,

                @Schema(description = "Default value") Object defaultValue,

                @Schema(description = "Description") String description) {
        public InputDefinitionDto {
                defaultValue = ExecutionPayloads.immutableValue(defaultValue);
        }
}
