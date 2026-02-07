package tech.kayys.gamelan.engine.node;

/**
 * Input/Output Definitions
 */
public record InputDefinition(
                String name,
                String type,
                boolean required,
                Object defaultValue,
                String description) {
}
