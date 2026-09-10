package tech.kayys.gamelan.engine.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import tech.kayys.gamelan.engine.error.ErrorCode;
import tech.kayys.gamelan.engine.error.GamelanException;

class RetryPolicyTest {

    @Test
    void constructorDefensivelyCopiesAndFreezesRetryableExceptions() {
        List<String> retryable = new ArrayList<>(List.of("IOException"));

        RetryPolicy policy = new RetryPolicy(
                3,
                Duration.ofSeconds(1),
                Duration.ofSeconds(10),
                2.0,
                retryable);

        retryable.add("TimeoutException");

        assertEquals(List.of("IOException"), policy.retryableExceptions());
        assertThrows(UnsupportedOperationException.class, () -> policy.retryableExceptions().add("x"));
    }

    @Test
    void calculateDelayAppliesBackoffAndCapsAtMaxDelay() {
        RetryPolicy policy = new RetryPolicy(
                5,
                Duration.ofMillis(100),
                Duration.ofMillis(250),
                2.0,
                List.of());

        assertEquals(Duration.ofMillis(100), policy.calculateDelay(1));
        assertEquals(Duration.ofMillis(200), policy.calculateDelay(2));
        assertEquals(Duration.ofMillis(250), policy.calculateDelay(3));
        assertEquals(Duration.ofMillis(250), policy.calculateDelay(10));
    }

    @Test
    void noneDisablesRetryAfterFirstAttempt() {
        RetryPolicy policy = RetryPolicy.none();

        assertFalse(policy.shouldRetry(1));
        assertEquals(Duration.ZERO, policy.calculateDelay(1));
    }

    @Test
    void constructorRejectsInvalidPolicyValues() {
        assertInvalid("maxAttempts", () -> new RetryPolicy(
                0,
                Duration.ZERO,
                Duration.ZERO,
                1.0,
                List.of()));
        assertInvalid("initialDelay", () -> new RetryPolicy(
                1,
                Duration.ofMillis(-1),
                Duration.ZERO,
                1.0,
                List.of()));
        assertInvalid("maxDelay", () -> new RetryPolicy(
                1,
                Duration.ofSeconds(2),
                Duration.ofSeconds(1),
                1.0,
                List.of()));
        assertInvalid("backoffMultiplier", () -> new RetryPolicy(
                1,
                Duration.ZERO,
                Duration.ZERO,
                0.9,
                List.of()));
        assertInvalid("retryableExceptions", () -> new RetryPolicy(
                1,
                Duration.ZERO,
                Duration.ZERO,
                1.0,
                List.of(" ")));
    }

    @Test
    void methodsRejectInvalidAttemptNumbers() {
        RetryPolicy policy = RetryPolicy.none();

        assertInvalid("attemptNumber", () -> policy.calculateDelay(0));
        assertInvalid("currentAttempt", () -> policy.shouldRetry(0));
    }

    private static void assertInvalid(String expectedMessagePart, ThrowingRunnable operation) {
        GamelanException exception = assertThrows(GamelanException.class, operation::run);

        assertEquals(ErrorCode.VALIDATION_FAILED, exception.getErrorCode());
        assertFalse(exception.isRetryable());
        assertEquals(400, exception.getHttpStatusCode());
        assertTrue(exception.getSafeMessage().contains(expectedMessagePart));
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run();
    }
}
