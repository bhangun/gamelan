package tech.kayys.gamelan.runtime.context;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Provides runtime-specific execution context.
 * Manages thread pools, resource limits, and timeouts.
 */
@ApplicationScoped
public class RuntimeExecutionContext {

    private static final Logger LOG = LoggerFactory.getLogger(RuntimeExecutionContext.class);
    private static final Duration DEFAULT_SHUTDOWN_GRACE_PERIOD = Duration.ofSeconds(5);
    private static final Duration FORCED_TERMINATION_GRACE_PERIOD = Duration.ofSeconds(1);
    private static final String THREAD_NAME_PREFIX = "gamelan-runtime-exec-";

    private final ExecutorService executorService;
    private final Map<String, Object> attributes;
    private final Duration defaultTimeout;
    private final int maxConcurrentTasks;
    private final AtomicBoolean shutdownStarted = new AtomicBoolean();

    @Inject
    Instance<MeterRegistry> meterRegistries;

    MeterRegistry meterRegistry;

    @ConfigProperty(name = "gamelan.runtime.execution.shutdown-grace-period", defaultValue = "5s")
    Duration shutdownGracePeriod = DEFAULT_SHUTDOWN_GRACE_PERIOD;

    public RuntimeExecutionContext() {
        this(defaultMaxConcurrentTasks());
    }

    private RuntimeExecutionContext(int maxConcurrentTasks) {
        this(
                Executors.newFixedThreadPool(maxConcurrentTasks, runtimeThreadFactory()),
                maxConcurrentTasks);
    }

    RuntimeExecutionContext(ExecutorService executorService, int maxConcurrentTasks) {
        this.maxConcurrentTasks = Math.max(1, maxConcurrentTasks);
        this.executorService = executorService;
        this.attributes = new ConcurrentHashMap<>();
        this.defaultTimeout = Duration.ofMinutes(5);

        LOG.info("RuntimeExecutionContext initialized with {} threads", this.maxConcurrentTasks);
    }

    /**
     * Get the executor service for async task execution
     */
    public ExecutorService getExecutorService() {
        return executorService;
    }

    /**
     * Get a context attribute
     */
    public Object getAttribute(String key) {
        return attributes.get(key);
    }

    /**
     * Set a context attribute
     */
    public void setAttribute(String key, Object value) {
        attributes.put(key, value);
    }

    /**
     * Get the default timeout for task execution
     */
    public Duration getDefaultTimeout() {
        return defaultTimeout;
    }

    /**
     * Get the maximum number of concurrent tasks
     */
    public int getMaxConcurrentTasks() {
        return maxConcurrentTasks;
    }

    public RuntimeExecutionStatus status() {
        ThreadPoolExecutor pool = executorService instanceof ThreadPoolExecutor threadPool
                ? threadPool
                : null;
        return new RuntimeExecutionStatus(
                executorService.getClass().getName(),
                THREAD_NAME_PREFIX,
                maxConcurrentTasks,
                defaultTimeout.toMillis(),
                effectiveShutdownGracePeriod(shutdownGracePeriod).toMillis(),
                shutdownStarted.get(),
                executorService.isShutdown(),
                executorService.isTerminated(),
                pool != null ? pool.getActiveCount() : null,
                pool != null ? pool.getPoolSize() : null,
                pool != null ? pool.getLargestPoolSize() : null,
                pool != null ? pool.getQueue().size() : null,
                pool != null ? pool.getTaskCount() : null,
                pool != null ? pool.getCompletedTaskCount() : null,
                Instant.now());
    }

    /**
     * Shutdown the execution context
     */
    public RuntimeShutdownResult shutdown() {
        return shutdown(shutdownGracePeriod);
    }

    public RuntimeShutdownResult shutdown(Duration gracePeriod) {
        Instant startedAt = Instant.now();
        RuntimeExecutionMetrics metrics = runtimeExecutionMetrics();
        Timer.Sample durationSample = metrics.start();
        if (!shutdownStarted.compareAndSet(false, true)) {
            return metrics.record(new RuntimeShutdownResult(
                    false,
                    executorService.isShutdown(),
                    executorService.isTerminated(),
                    false,
                    0,
                    false,
                    null,
                    startedAt,
                    Instant.now()), durationSample);
        }

        Duration effectiveGracePeriod = effectiveShutdownGracePeriod(gracePeriod);
        LOG.info("Shutting down RuntimeExecutionContext with gracePeriod={}", effectiveGracePeriod);

        boolean forced = false;
        int cancelledTasks = 0;
        boolean interrupted = false;
        String error = null;
        executorService.shutdown();
        try {
            if (!awaitTermination(effectiveGracePeriod)) {
                forced = true;
                cancelledTasks = executorService.shutdownNow().size();
                if (!awaitTermination(FORCED_TERMINATION_GRACE_PERIOD)) {
                    LOG.warn("RuntimeExecutionContext did not terminate after forced shutdown; queuedTasksCancelled={}",
                            cancelledTasks);
                }
            }
        } catch (InterruptedException interruptedError) {
            interrupted = true;
            forced = true;
            cancelledTasks = executorService.shutdownNow().size();
            Thread.currentThread().interrupt();
            LOG.warn("Interrupted while shutting down RuntimeExecutionContext; queuedTasksCancelled={}",
                    cancelledTasks);
        } catch (RuntimeException runtimeError) {
            error = errorSummary(runtimeError);
            LOG.warn("RuntimeExecutionContext shutdown failed: {}", error);
            LOG.debug("RuntimeExecutionContext shutdown failure details", runtimeError);
        }

        return metrics.record(new RuntimeShutdownResult(
                true,
                executorService.isShutdown(),
                executorService.isTerminated(),
                forced,
                cancelledTasks,
                interrupted,
                error,
                startedAt,
                Instant.now()), durationSample);
    }

    private boolean awaitTermination(Duration timeout) throws InterruptedException {
        long timeoutMillis = timeout.isZero() ? 0L : Math.max(1L, timeout.toMillis());
        return executorService.awaitTermination(timeoutMillis, TimeUnit.MILLISECONDS);
    }

    private static Duration effectiveShutdownGracePeriod(Duration gracePeriod) {
        return gracePeriod != null && !gracePeriod.isNegative()
                ? gracePeriod
                : DEFAULT_SHUTDOWN_GRACE_PERIOD;
    }

    private static String errorSummary(Throwable error) {
        String message = error != null ? error.getMessage() : null;
        return message == null || message.isBlank()
                ? (error != null ? error.getClass().getSimpleName() : "unknown")
                : error.getClass().getSimpleName() + ": " + message.replaceAll("\\s+", " ").trim();
    }

    private static int defaultMaxConcurrentTasks() {
        return Math.max(1, Runtime.getRuntime().availableProcessors() * 2);
    }

    private static ThreadFactory runtimeThreadFactory() {
        AtomicInteger sequence = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, THREAD_NAME_PREFIX + sequence.incrementAndGet());
            thread.setDaemon(false);
            thread.setUncaughtExceptionHandler((failedThread, error) -> {
                LOG.error("Uncaught runtime execution context failure on {}", failedThread.getName(), error);
            });
            return thread;
        };
    }

    private RuntimeExecutionMetrics runtimeExecutionMetrics() {
        return new RuntimeExecutionMetrics(meterRegistry());
    }

    private MeterRegistry meterRegistry() {
        if (meterRegistry != null) {
            return meterRegistry;
        }
        if (meterRegistries == null || meterRegistries.isUnsatisfied() || meterRegistries.isAmbiguous()) {
            return null;
        }
        return meterRegistries.get();
    }

    private static final class RuntimeExecutionMetrics {
        private final MeterRegistry registry;

        private RuntimeExecutionMetrics(MeterRegistry registry) {
            this.registry = registry;
        }

        private Timer.Sample start() {
            return registry != null ? Timer.start(registry) : null;
        }

        private RuntimeShutdownResult record(RuntimeShutdownResult result, Timer.Sample sample) {
            if (registry == null || result == null) {
                return result;
            }
            String outcome = outcome(result);
            String forced = Boolean.toString(result.forced());
            String interrupted = Boolean.toString(result.interrupted());
            Counter.builder("gamelan.runtime.execution.shutdowns")
                    .description("Runtime execution context shutdown attempts")
                    .tag("outcome", outcome)
                    .tag("forced", forced)
                    .tag("interrupted", interrupted)
                    .register(registry)
                    .increment();
            if (result.cancelledTasks() > 0) {
                Counter.builder("gamelan.runtime.execution.shutdown.cancelled_tasks")
                        .description("Runtime execution context queued tasks cancelled during forced shutdown")
                        .tag("outcome", outcome)
                        .tag("interrupted", interrupted)
                        .register(registry)
                        .increment(result.cancelledTasks());
            }
            if (sample != null) {
                sample.stop(Timer.builder("gamelan.runtime.execution.shutdown.duration")
                        .description("Runtime execution context shutdown duration")
                        .tag("outcome", outcome)
                        .tag("forced", forced)
                        .tag("interrupted", interrupted)
                        .register(registry));
            }
            return result;
        }

        private static String outcome(RuntimeShutdownResult result) {
            if (!result.initiated()) {
                return "already_shutdown";
            }
            if (result.error() != null) {
                return "failed";
            }
            return result.terminated() ? "terminated" : "not_terminated";
        }
    }

    public record RuntimeExecutionStatus(
            String executorImplementation,
            String threadNamePrefix,
            int maxConcurrentTasks,
            long defaultTimeoutMillis,
            long shutdownGracePeriodMillis,
            boolean shutdownStarted,
            boolean shutdown,
            boolean terminated,
            Integer activeThreads,
            Integer poolSize,
            Integer largestPoolSize,
            Integer queuedTasks,
            Long scheduledTasks,
            Long completedTasks,
            Instant observedAt) {
    }

    public record RuntimeShutdownResult(
            boolean initiated,
            boolean shutdown,
            boolean terminated,
            boolean forced,
            int cancelledTasks,
            boolean interrupted,
            String error,
            Instant startedAt,
            Instant completedAt) {
    }
}
