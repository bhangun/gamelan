package tech.kayys.gamelan.registry.metrics;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Metrics service for executor registry
 */
@ApplicationScoped
public class RegistryMetricsService {

    @Inject
    MeterRegistry meterRegistry;

    private Counter registrationCounter;
    private Counter unregistrationCounter;
    private Counter heartbeatCounter;
    private Counter selectionCounter;
    private Timer selectionTimer;
    private AtomicInteger executorCount;

    private static void safeIncrement(Counter counter) {
        if (counter != null) {
            counter.increment();
        }
    }

    public void initialize(Supplier<Integer> executorCountSupplier) {
        registrationCounter = Counter.builder("executor.registrations")
                .description("Number of executor registrations")
                .register(meterRegistry);

        unregistrationCounter = Counter.builder("executor.unregistrations")
                .description("Number of executor unregistrations")
                .register(meterRegistry);

        heartbeatCounter = Counter.builder("executor.heartbeats")
                .description("Number of executor heartbeats received")
                .register(meterRegistry);

        selectionCounter = Counter.builder("executor.selections")
                .description("Number of executor selections made")
                .register(meterRegistry);

        selectionTimer = Timer.builder("executor.selection.duration")
                .description("Time taken to select an executor")
                .register(meterRegistry);

        // Create gauge that samples the executor count dynamically
        executorCount = new AtomicInteger(0);
        Gauge.builder("executor.count", executorCount, Number::doubleValue)
                .description("Current number of registered executors")
                .register(meterRegistry);
    }

    public void incrementRegistration() {
        safeIncrement(registrationCounter);
    }

    public void incrementUnregistration() {
        safeIncrement(unregistrationCounter);
    }

    public void incrementHeartbeat() {
        safeIncrement(heartbeatCounter);
    }

    public void incrementSelection() {
        safeIncrement(selectionCounter);
    }

    public Timer.Sample startSelectionTimer() {
        if (meterRegistry == null) {
            return null;
        }
        return Timer.start(meterRegistry);
    }

    public void stopSelectionTimer(Timer.Sample sample) {
        if (sample != null && selectionTimer != null) {
            sample.stop(selectionTimer);
        }
    }

    public void incrementExecutorCount() {
        if (executorCount != null) {
            executorCount.incrementAndGet();
        }
    }

    public void decrementExecutorCount() {
        if (executorCount != null) {
            executorCount.decrementAndGet();
        }
    }
}
