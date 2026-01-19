package tech.kayys.gamelan.engine.run.dto;

import java.time.Instant;
import java.util.Map;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Trigger information
 */
@Schema(description = "Information about what triggered the workflow")
public record TriggerInfo(
        @Schema(description = "Trigger type", example = "API") String type,

        @Schema(description = "Triggered by user/system", example = "user-123") String triggeredBy,

        @Schema(description = "Trigger timestamp") Instant triggeredAt,

        @Schema(description = "Additional trigger metadata") Map<String, String> metadata) {
}
