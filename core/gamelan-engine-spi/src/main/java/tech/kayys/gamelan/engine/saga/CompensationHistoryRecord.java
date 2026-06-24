package tech.kayys.gamelan.engine.saga;

import java.util.Map;

import tech.kayys.gamelan.engine.payload.ExecutionPayloads;

/**
 * Typed append request for a compensation execution-history event.
 */
public record CompensationHistoryRecord(
        String eventType,
        String message,
        Map<String, Object> metadata) {
    public CompensationHistoryRecord {
        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("eventType cannot be blank");
        }
        message = message != null ? message : "";
        metadata = ExecutionPayloads.immutableMap(metadata);
    }
}
