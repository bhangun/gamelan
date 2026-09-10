package tech.kayys.gamelan.engine.saga;

import java.time.Duration;

/**
 * Compensation Policy - Saga pattern configuration
 */
public record CompensationPolicy(
        boolean enabled,
        CompensationStrategy strategy,
        Duration timeout,
        boolean failOnCompensationError,
        int maxRetries) {

    public static CompensationPolicy disabled() {
        return new CompensationPolicy(
                false,
                CompensationStrategy.SEQUENTIAL,
                Duration.ZERO,
                false,
                0);
    }

    public static CompensationPolicy enabledDefault() {
        return new CompensationPolicy(
                true,
                CompensationStrategy.SEQUENTIAL,
                Duration.ofMinutes(10),
                true,
                3);
    }

    public static CompensationPolicy enabled(CompensationStrategy strategy, Duration timeout,
            boolean failOnCompensationError, int maxRetries) {
        return new CompensationPolicy(true, strategy, timeout, failOnCompensationError, maxRetries);
    }
}
