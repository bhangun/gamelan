package tech.kayys.silat.model;

import java.util.Objects;

/**
 * Node Identifier within a workflow
 */
public record NodeId(String value) {
    public NodeId {
        Objects.requireNonNull(value, "NodeId cannot be null");
    }

    public static NodeId of(String value) {
        return new NodeId(value);
    }
}