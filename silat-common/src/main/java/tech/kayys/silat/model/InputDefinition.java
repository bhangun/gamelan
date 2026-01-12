package tech.kayys.silat.model;

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
