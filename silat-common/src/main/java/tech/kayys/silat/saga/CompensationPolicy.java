package tech.kayys.silat.saga;

import java.time.Duration;

/**
 * Compensation Policy - Saga pattern configuration
 */
public record CompensationPolicy(
        boolean enabled,
        CompensationStrategy strategy,
        Duration timeout,
        boolean failOnCompensationError) {
    public enum CompensationStrategy {
        SEQUENTIAL, // Compensate in reverse order
        PARALLEL, // Compensate all in parallel
        CUSTOM // Custom compensation logic
    }

    public static CompensationPolicy disabled() {
        return new CompensationPolicy(
                false,
                CompensationStrategy.SEQUENTIAL,
                Duration.ZERO,
                false);
    }

    public static CompensationPolicy enabledDefault() {
        return new CompensationPolicy(
                true,
                CompensationStrategy.SEQUENTIAL,
                Duration.ofMinutes(10),
                true);
    }
}
