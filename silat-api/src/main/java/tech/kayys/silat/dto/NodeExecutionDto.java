package tech.kayys.silat.dto;

import java.time.Instant;
import java.util.Map;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Node execution DTO
 */
@Schema(description = "Node execution state")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NodeExecutionDto(
        @Schema(description = "Node ID") String nodeId,

        @Schema(description = "Node name") String nodeName,

        @Schema(description = "Execution status") String status,

        @Schema(description = "Attempt number") int attempt,

        @Schema(description = "Started timestamp") Instant startedAt,

        @Schema(description = "Completed timestamp") Instant completedAt,

        @Schema(description = "Duration in milliseconds") Long durationMs,

        @Schema(description = "Output data") Map<String, Object> output,

        @Schema(description = "Error information") ErrorDto error) {
}
