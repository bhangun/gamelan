package tech.kayys.gamelan.engine.signal;

import java.time.Instant;
import java.util.Map;

import tech.kayys.gamelan.engine.node.NodeId;

/**
 * Signal - External signal to resume workflow
 */
public record Signal(
                String name,
                NodeId targetNodeId,
                Map<String, Object> payload,
                Instant timestamp) {
}
