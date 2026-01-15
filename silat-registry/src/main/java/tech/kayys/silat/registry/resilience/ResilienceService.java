package tech.kayys.silat.registry.resilience;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Resilience service for the executor registry
 */
@ApplicationScoped
public class ResilienceService {

    // Circuit breaker state tracking
    private final Map<String, CircuitBreakerState> circuitBreakers = new ConcurrentHashMap<>();

    /**
     * Execute operation with retry logic
     */
    private <T> Uni<T> withRetry(Uni<T> operation, int maxAttempts) {
        if (maxAttempts <= 1) {
            return operation;
        }
        return operation.onFailure().retry().atMost(maxAttempts);
    }

    /**
     * Execute operation with circuit breaker
     */
    private <T> Uni<T> withCircuitBreaker(Uni<T> operation, String breakerName) {
        CircuitBreakerState state = circuitBreakers.computeIfAbsent(breakerName, k -> new CircuitBreakerState());

        if (state.isOpen()) {
            if (state.canAttemptReset()) {
                state.trial();
            } else {
                return Uni.createFrom().failure(new IllegalStateException("Circuit breaker open: " + breakerName));
            }
        }

        return operation
                .onFailure().invoke(t -> state.recordFailure())
                .onItem().invoke(item -> state.recordSuccess());
    }

    /**
     * Execute operation with timeout
     */
    public <T> Uni<T> withTimeout(Uni<T> operation, Duration timeout) {
        return operation
                .ifNoItem().after(timeout)
                .failWith(() -> new RuntimeException("Operation timed out after " + timeout));
    }

    /**
     * Execute operation with retry and circuit breaker
     */
    public <T> Uni<T> withResiliencePattern(
            Uni<T> operation,
            String breakerName,
            int maxAttempts,
            Duration timeout) {

        Uni<T> retried = withRetry(operation, maxAttempts);
        Uni<T> timed = withTimeout(retried, timeout);
        return withCircuitBreaker(timed, breakerName);
    }

    /**
     * Circuit breaker state
     */
    private static class CircuitBreakerState {
        private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
        private final AtomicInteger failureCount = new AtomicInteger(0);
        private final AtomicLong lastFailureTime = new AtomicLong(0);
        private static final long RESET_TIMEOUT_MS = 30000; // 30 seconds
        private static final int FAILURE_THRESHOLD = 5;

        enum State {
            CLOSED, OPEN, HALF_OPEN
        }

        boolean isOpen() {
            return state.get() == State.OPEN;
        }

        boolean canAttemptReset() {
            long last = lastFailureTime.get();
            return last > 0 && (System.currentTimeMillis() - last) > RESET_TIMEOUT_MS;
        }

        void trial() {
            state.compareAndSet(State.OPEN, State.HALF_OPEN);
        }

        void recordFailure() {
            int count = failureCount.incrementAndGet();
            lastFailureTime.set(System.currentTimeMillis());

            if (count >= FAILURE_THRESHOLD) {
                state.set(State.OPEN);
            }
        }

        void recordSuccess() {
            failureCount.set(0);
            state.set(State.CLOSED);
        }
    }
}