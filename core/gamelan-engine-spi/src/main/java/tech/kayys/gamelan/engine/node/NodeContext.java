package tech.kayys.gamelan.engine.node;

import java.util.Map;
import java.util.Objects;

import tech.kayys.gamelan.engine.error.ErrorCode;
import tech.kayys.gamelan.engine.error.GamelanException;
import tech.kayys.gamelan.engine.payload.ExecutionPayloads;

public record NodeContext(
        NodeId nodeId,
        String nodeType,
        Map<String, Object> input,
        Map<String, Object> metadata) {

    public NodeContext {
        Objects.requireNonNull(nodeId, "NodeId cannot be null");
        if (nodeType == null || nodeType.isBlank()) {
            throw new GamelanException(ErrorCode.VALIDATION_FAILED, "NodeContext nodeType is required");
        }
        nodeType = nodeType.trim();
        input = ExecutionPayloads.immutableMap(input);
        metadata = ExecutionPayloads.immutableMap(metadata);
    }
}
