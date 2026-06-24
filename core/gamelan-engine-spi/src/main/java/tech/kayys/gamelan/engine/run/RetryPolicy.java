package tech.kayys.gamelan.engine.run;

import java.time.Duration;
import java.util.List;

import tech.kayys.gamelan.engine.error.ErrorCode;
import tech.kayys.gamelan.engine.error.GamelanException;

/**
 * Retry Policy - Configurable retry behavior
 */
public record RetryPolicy(
        int maxAttempts,
        Duration initialDelay,
        Duration maxDelay,
        double backoffMultiplier,
        List<String> retryableExceptions) {
    public static final RetryPolicy DEFAULT = new RetryPolicy(
            3,
            Duration.ofSeconds(1),
            Duration.ofMinutes(5),
            2.0,
            List.of());

    public RetryPolicy {
        if (maxAttempts < 1) {
            throw invalid("maxAttempts must be at least 1");
        }
        if (initialDelay == null) {
            throw invalid("initialDelay cannot be null");
        }
        if (maxDelay == null) {
            throw invalid("maxDelay cannot be null");
        }
        if (initialDelay.isNegative()) {
            throw invalid("initialDelay cannot be negative");
        }
        if (maxDelay.isNegative()) {
            throw invalid("maxDelay cannot be negative");
        }
        if (maxDelay.compareTo(initialDelay) < 0) {
            throw invalid("maxDelay cannot be less than initialDelay");
        }
        if (!Double.isFinite(backoffMultiplier) || backoffMultiplier < 1.0) {
            throw invalid("backoffMultiplier must be finite and at least 1.0");
        }

        if (retryableExceptions != null
                && retryableExceptions.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw invalid("retryableExceptions cannot contain null or blank values");
        }
        retryableExceptions = retryableExceptions != null ? List.copyOf(retryableExceptions) : List.of();
    }

    public static RetryPolicy none() {
        return new RetryPolicy(1, Duration.ZERO, Duration.ZERO, 1.0, List.of());
    }

    public Duration calculateDelay(int attemptNumber) {
        if (attemptNumber < 1) {
            throw invalid("attemptNumber must be at least 1");
        }
        if (attemptNumber <= 1)
            return initialDelay;

        long delayMillis = (long) (initialDelay.toMillis() *
                Math.pow(backoffMultiplier, attemptNumber - 1));

        return Duration.ofMillis(Math.min(delayMillis, maxDelay.toMillis()));
    }

    public boolean shouldRetry(int currentAttempt) {
        if (currentAttempt < 1) {
            throw invalid("currentAttempt must be at least 1");
        }
        return currentAttempt < maxAttempts;
    }

    private static GamelanException invalid(String message) {
        return new GamelanException(ErrorCode.VALIDATION_FAILED, "Invalid retry policy: " + message);
    }
}
