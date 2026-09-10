package tech.kayys.gamelan.runtime.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tech.kayys.gamelan.runtime.context.RuntimeExecutionContext.RuntimeExecutionStatus;
import tech.kayys.gamelan.runtime.context.RuntimeExecutionContext.RuntimeShutdownResult;

class RuntimeExecutionContextTest {

    @Test
    void defaultExecutorUsesNamedNonDaemonThreads() throws Exception {
        RuntimeExecutionContext context = new RuntimeExecutionContext();
        try {
            String threadName = context.getExecutorService()
                    .submit(() -> Thread.currentThread().getName())
                    .get(1, TimeUnit.SECONDS);
            boolean daemon = context.getExecutorService()
                    .submit(() -> Thread.currentThread().isDaemon())
                    .get(1, TimeUnit.SECONDS);

            assertTrue(threadName.startsWith("gamelan-runtime-exec-"));
            assertFalse(daemon);
            assertTrue(context.getMaxConcurrentTasks() >= 1);
        } finally {
            context.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void statusReportsExecutorConfigurationAndPoolState() {
        RuntimeExecutionContext context = new RuntimeExecutionContext(
                Executors.newFixedThreadPool(1),
                1);
        try {
            RuntimeExecutionStatus status = context.status();

            assertEquals(1, status.maxConcurrentTasks());
            assertEquals(300000, status.defaultTimeoutMillis());
            assertEquals(5000, status.shutdownGracePeriodMillis());
            assertEquals(false, status.shutdownStarted());
            assertEquals(false, status.shutdown());
            assertEquals(false, status.terminated());
            assertNotNull(status.executorImplementation());
            assertEquals("gamelan-runtime-exec-", status.threadNamePrefix());
            assertNotNull(status.observedAt());
        } finally {
            context.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void shutdownTerminatesIdleExecutorWithinGracePeriod() {
        RuntimeExecutionContext context = new RuntimeExecutionContext(
                Executors.newSingleThreadExecutor(),
                1);

        RuntimeShutdownResult result = context.shutdown(Duration.ofSeconds(1));

        assertTrue(result.initiated());
        assertTrue(result.shutdown());
        assertTrue(result.terminated());
        assertFalse(result.forced());
        assertEquals(0, result.cancelledTasks());
        assertFalse(result.interrupted());
        assertNull(result.error());
        assertNotNull(result.startedAt());
        assertNotNull(result.completedAt());
    }

    @Test
    void shutdownIsIdempotentAfterFirstCall() {
        RuntimeExecutionContext context = new RuntimeExecutionContext(
                Executors.newSingleThreadExecutor(),
                1);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        context.meterRegistry = meterRegistry;

        RuntimeShutdownResult first = context.shutdown(Duration.ofSeconds(1));
        RuntimeShutdownResult second = context.shutdown(Duration.ofSeconds(1));

        assertTrue(first.initiated());
        assertFalse(second.initiated());
        assertTrue(second.shutdown());
        assertTrue(second.terminated());
        assertFalse(second.forced());
        assertEquals(0, second.cancelledTasks());
        assertNull(second.error());
        assertEquals(1.0, counter(
                meterRegistry,
                "gamelan.runtime.execution.shutdowns",
                "outcome", "terminated",
                "forced", "false",
                "interrupted", "false"));
        assertEquals(1.0, counter(
                meterRegistry,
                "gamelan.runtime.execution.shutdowns",
                "outcome", "already_shutdown",
                "forced", "false",
                "interrupted", "false"));
        assertEquals(1, timerCount(
                meterRegistry,
                "gamelan.runtime.execution.shutdown.duration",
                "outcome", "terminated",
                "forced", "false",
                "interrupted", "false"));
    }

    @Test
    void shutdownForcesQueuedWorkAfterGracePeriod() throws InterruptedException {
        RuntimeExecutionContext context = new RuntimeExecutionContext(
                Executors.newSingleThreadExecutor(),
                1);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        context.meterRegistry = meterRegistry;
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        AtomicBoolean queuedTaskRan = new AtomicBoolean();

        context.getExecutorService().submit(() -> {
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException error) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
            }
        });
        context.getExecutorService().submit(() -> queuedTaskRan.set(true));
        assertTrue(started.await(1, TimeUnit.SECONDS));

        RuntimeShutdownResult result = context.shutdown(Duration.ZERO);

        assertTrue(result.initiated());
        assertTrue(result.shutdown());
        assertTrue(result.terminated());
        assertTrue(result.forced());
        assertEquals(1, result.cancelledTasks());
        assertFalse(result.interrupted());
        assertNull(result.error());
        assertTrue(interrupted.get());
        assertFalse(queuedTaskRan.get());
        assertEquals(1.0, counter(
                meterRegistry,
                "gamelan.runtime.execution.shutdowns",
                "outcome", "terminated",
                "forced", "true",
                "interrupted", "false"));
        assertEquals(1.0, counter(
                meterRegistry,
                "gamelan.runtime.execution.shutdown.cancelled_tasks",
                "outcome", "terminated",
                "interrupted", "false"));
    }

    private static double counter(SimpleMeterRegistry meterRegistry, String name, String... tags) {
        var counter = meterRegistry.find(name).tags(tags).counter();
        return counter != null ? counter.count() : 0.0;
    }

    private static long timerCount(SimpleMeterRegistry meterRegistry, String name, String... tags) {
        var timer = meterRegistry.find(name).tags(tags).timer();
        return timer != null ? timer.count() : 0;
    }
}
