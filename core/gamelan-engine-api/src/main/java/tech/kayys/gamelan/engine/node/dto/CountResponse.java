package tech.kayys.gamelan.engine.node.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Count response
 */
@Schema(description = "Count response")
public record CountResponse(
        @Schema(description = "Count value") long count) {
}
