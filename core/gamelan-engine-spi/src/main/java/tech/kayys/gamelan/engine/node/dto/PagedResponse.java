package tech.kayys.gamelan.engine.node.dto;

import java.util.List;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import tech.kayys.gamelan.engine.payload.ExecutionPayloads;

/**
 * Paged response wrapper
 */
@Schema(description = "Paged response")
public record PagedResponse<T>(
        @Schema(description = "Page content") List<T> content,

        @Schema(description = "Page number") int page,

        @Schema(description = "Page size") int size,

        @Schema(description = "Total elements in page") int totalElements,

        @Schema(description = "Has more pages") boolean hasMore) {
    public PagedResponse {
        content = ExecutionPayloads.immutableListCopy(content);
    }
}
