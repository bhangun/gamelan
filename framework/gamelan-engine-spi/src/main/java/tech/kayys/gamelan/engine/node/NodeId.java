package tech.kayys.gamelan.engine.node;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Objects;
import tech.kayys.gamelan.engine.error.ErrorCode;
import tech.kayys.gamelan.engine.error.GamelanException;

/**
 * Node Identifier within a workflow
 */
public record NodeId(@JsonValue String value) {
    public NodeId {
        Objects.requireNonNull(value, "NodeId cannot be null");
        if (value.isBlank()) {
            throw new GamelanException(ErrorCode.VALIDATION_FAILED, "NodeId cannot be blank");
        }
    }

    @com.fasterxml.jackson.annotation.JsonCreator
    public static NodeId of(String value) {
        return new NodeId(value);
    }
}
