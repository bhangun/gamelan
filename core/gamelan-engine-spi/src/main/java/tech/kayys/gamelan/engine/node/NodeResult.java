package tech.kayys.gamelan.engine.node;

import java.time.Instant;
import java.util.Map;

public record NodeResult(
        boolean success,
        Object output,
        Map<String, Object> metadata,
        Instant completedAt) {
    public static NodeResult success(Object output) {
        return new NodeResult(true, output, Map.of(), Instant.now());
    }

    public static NodeResult failure(String error) {
        return new NodeResult(false, null, Map.of("error", error), Instant.now());
    }
}