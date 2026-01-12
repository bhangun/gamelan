package tech.kayys.silat.dto;

import java.util.List;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Execution history response
 */
@Schema(description = "Execution history response")
public record ExecutionHistoryResponse(
        @Schema(description = "Run ID") String runId,

        @Schema(description = "Events") List<ExecutionEventDto> events,

        @Schema(description = "Total event count") int totalEvents) {
}
