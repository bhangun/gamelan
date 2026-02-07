package tech.kayys.gamelan.engine.run;

import tech.kayys.gamelan.engine.node.NodeId;

/**
 * Transition - Conditional flow between nodes
 */
/**
 * Transition - Conditional flow between nodes
 */
public record Transition(
        NodeId targetNodeId,
        String condition,
        TransitionType type) {

    public enum TransitionType {
        SUCCESS,
        FAILURE,
        CONDITION,
        DEFAULT
    }

    public boolean isDefault() {
        return type == TransitionType.DEFAULT;
    }

    public boolean isConditional() {
        return type == TransitionType.CONDITION;
    }

    public boolean isSuccess() {
        return type == TransitionType.SUCCESS;
    }

    public boolean isFailure() {
        return type == TransitionType.FAILURE;
    }
}
