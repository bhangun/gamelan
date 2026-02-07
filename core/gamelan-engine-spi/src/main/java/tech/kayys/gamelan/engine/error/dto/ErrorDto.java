package tech.kayys.gamelan.engine.error.dto;

import java.util.Map;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Error DTO
 */
@Schema(description = "Error information")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorDto(
        @Schema(description = "Error code") String code,

        @Schema(description = "Error message") String message,

        @Schema(description = "Stack trace") String stackTrace,

        @Schema(description = "Additional context") Map<String, Object> context) {
}