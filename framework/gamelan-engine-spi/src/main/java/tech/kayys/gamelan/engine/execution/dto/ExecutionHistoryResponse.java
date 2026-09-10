package tech.kayys.gamelan.engine.execution.dto;

import java.util.List;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import tech.kayys.gamelan.engine.payload.ExecutionPayloads;

/**
 * Execution history response
 */
@Schema(description = "Execution history response")
public record ExecutionHistoryResponse(
        @Schema(description = "Run ID") String runId,

        @Schema(description = "Events") List<ExecutionEventDto> events,

        @Schema(description = "Total event count") int totalEvents) {
    public ExecutionHistoryResponse {
        events = ExecutionPayloads.immutableListCopy(events);
    }
}
