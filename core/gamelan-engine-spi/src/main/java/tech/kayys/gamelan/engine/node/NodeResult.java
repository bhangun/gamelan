package tech.kayys.gamelan.engine.node;

import java.time.Instant;
import java.util.Map;

import tech.kayys.gamelan.engine.payload.ExecutionPayloads;

public record NodeResult(
        boolean success,
        Object output,
        Map<String, Object> metadata,
        Instant completedAt) {

    public NodeResult {
        output = ExecutionPayloads.immutableValue(output);
        metadata = ExecutionPayloads.immutableMap(metadata);
        completedAt = completedAt != null ? completedAt : Instant.now();
    }

    public static NodeResult success(Object output) {
        return new NodeResult(true, output, Map.of(), Instant.now());
    }

    public static NodeResult failure(String error) {
        String message = error != null && !error.isBlank() ? error : "Node execution failed";
        return new NodeResult(false, null, Map.of("error", message), Instant.now());
    }
}
