package tech.kayys.gamelan.engine.node;

import java.util.Map;

public record NodeContext(
                NodeId nodeId,
                String nodeType,
                Map<String, Object> input,
                Map<String, Object> metadata) {
}