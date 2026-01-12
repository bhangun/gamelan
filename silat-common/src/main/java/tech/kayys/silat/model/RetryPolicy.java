package tech.kayys.silat.model;

import java.time.Duration;
import java.util.List;

/**
 * Retry Policy - Configurable retry behavior
 */
public record RetryPolicy(
        int maxAttempts,
        Duration initialDelay,
        Duration maxDelay,
        double backoffMultiplier,
        List<String> retryableExceptions) {
    public static RetryPolicy DEFAULT = new RetryPolicy(
            3,
            Duration.ofSeconds(1),
            Duration.ofMinutes(5),
            2.0,
            List.of());

    public static RetryPolicy none() {
        return new RetryPolicy(1, Duration.ZERO, Duration.ZERO, 1.0, List.of());
    }

    public Duration calculateDelay(int attemptNumber) {
        if (attemptNumber <= 1)
            return initialDelay;

        long delayMillis = (long) (initialDelay.toMillis() *
                Math.pow(backoffMultiplier, attemptNumber - 1));

        return Duration.ofMillis(Math.min(delayMillis, maxDelay.toMillis()));
    }

    public boolean shouldRetry(int currentAttempt) {
        return currentAttempt < maxAttempts;
    }
}
