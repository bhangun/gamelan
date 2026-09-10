package tech.kayys.gamelan.engine.signal;

import java.time.Instant;
import java.util.Map;

import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.payload.ExecutionPayloads;

/**
 * Signal - External signal to resume workflow
 */
public record Signal(
                String name,
                NodeId targetNodeId,
                Map<String, Object> payload,
                Instant timestamp,
                String idempotencyKey) {
    public Signal(String name, NodeId targetNodeId, Map<String, Object> payload, Instant timestamp) {
        this(name, targetNodeId, payload, timestamp, null);
    }

    public Signal {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Signal name cannot be null or blank");
        }
        name = name.trim();
        payload = ExecutionPayloads.immutableMap(payload);
        timestamp = timestamp != null ? timestamp : Instant.now();
        idempotencyKey = idempotencyKey != null && !idempotencyKey.isBlank() ? idempotencyKey.trim() : null;
    }
}
