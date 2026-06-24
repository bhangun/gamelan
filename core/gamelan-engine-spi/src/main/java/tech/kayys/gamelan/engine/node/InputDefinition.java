package tech.kayys.gamelan.engine.node;

import tech.kayys.gamelan.engine.payload.ExecutionPayloads;

/**
 * Input/Output Definitions
 */
public record InputDefinition(
                String name,
                String type,
                boolean required,
                Object defaultValue,
                String description) {
    public InputDefinition {
        defaultValue = ExecutionPayloads.immutableValue(defaultValue);
    }
}
