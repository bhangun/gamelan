package tech.kayys.silat.dto;

import java.time.Instant;
import java.util.Map;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Execution event DTO
 */
@Schema(description = "Execution event")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExecutionEventDto(
        @Schema(description = "Event ID") String eventId,

        @Schema(description = "Event type") String eventType,

        @Schema(description = "Sequence number") long sequenceNumber,

        @Schema(description = "Occurred timestamp") Instant occurredAt,

        @Schema(description = "Event data") Map<String, Object> eventData) {
}
