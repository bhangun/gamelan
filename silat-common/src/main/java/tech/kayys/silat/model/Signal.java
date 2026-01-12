package tech.kayys.silat.model;

import java.time.Instant;
import java.util.Map;

/**
 * Signal - External signal to resume workflow
 */
public record Signal(
        String name,
        NodeId targetNodeId,
        Map<String, Object> payload,
        Instant timestamp) {
}
