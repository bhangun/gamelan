package tech.kayys.silat.model;

import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Objects;

/**
 * Node Identifier within a workflow
 */
public record NodeId(@JsonValue String value) {
    public NodeId {
        Objects.requireNonNull(value, "NodeId cannot be null");
    }

    public static NodeId of(String value) {
        return new NodeId(value);
    }
}