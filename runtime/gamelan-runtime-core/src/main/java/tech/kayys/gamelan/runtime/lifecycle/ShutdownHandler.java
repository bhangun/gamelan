package tech.kayys.gamelan.runtime.lifecycle;

import java.time.Instant;

import io.quarkus.runtime.ShutdownEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tech.kayys.gamelan.runtime.context.RuntimeExecutionContext;
import tech.kayys.gamelan.runtime.context.RuntimeExecutionContext.RuntimeShutdownResult;
import tech.kayys.gamelan.scheduler.TaskWorker;
import tech.kayys.gamelan.scheduler.TaskWorker.WorkerControlResult;

/**
 * Handles graceful shutdown of the runtime.
 */
@ApplicationScoped
public class ShutdownHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ShutdownHandler.class);

    @Inject
    Instance<TaskWorker> taskWorkers;

    @Inject
    Instance<RuntimeExecutionContext> runtimeContexts;

    void onStop(@Observes ShutdownEvent ev) {
        LOG.info("The Gamelan Runtime is stopping...");
        ShutdownResult result = shutdownRuntime();
        LOG.info("Shutdown sequence initiated: taskWorkerAvailable={} taskWorkerDrainAccepted={} "
                        + "taskWorkerDrainCompleted={} "
                        + "runtimeContextAvailable={} runtimeContextShutdown={} "
                        + "runtimeContextTerminated={} runtimeContextForced={}",
                result.taskWorkerAvailable(),
                result.taskWorkerDrainAccepted(),
                result.taskWorkerDrainCompleted(),
                result.runtimeContextAvailable(),
                result.runtimeContextShutdown(),
                result.runtimeContextTerminated(),
                result.runtimeContextForced());
    }

    ShutdownResult shutdownRuntime() {
        WorkerControlResult workerResult = null;
        String workerError = null;
        RuntimeExecutionContext runtimeContext = null;
        RuntimeShutdownResult contextResult = null;
        String contextError = null;

        TaskWorker taskWorker = resolvable(taskWorkers);
        if (taskWorker != null) {
            try {
                workerResult = taskWorker.drain();
                LOG.info("Task Worker shutdown drain: accepted={} completed={} reason={} state={} inFlight={}",
                        workerResult.accepted(),
                        workerResult.completed(),
                        workerResult.reason(),
                        workerResult.status().state(),
                        workerResult.status().inFlightCount());
            } catch (RuntimeException error) {
                workerError = errorSummary(error);
                LOG.warn("Task Worker shutdown drain failed: {}", workerError);
                LOG.debug("Task Worker shutdown drain failure details", error);
            }
        } else {
            LOG.debug("No Task Worker bean is available during shutdown.");
        }

        runtimeContext = resolvable(runtimeContexts);
        if (runtimeContext != null) {
            try {
                contextResult = runtimeContext.shutdown();
                contextError = contextResult.error();
                LOG.info("Runtime execution context shutdown: initiated={} shutdown={} terminated={} forced={} "
                                + "cancelledTasks={}",
                        contextResult.initiated(),
                        contextResult.shutdown(),
                        contextResult.terminated(),
                        contextResult.forced(),
                        contextResult.cancelledTasks());
            } catch (RuntimeException error) {
                contextError = errorSummary(error);
                LOG.warn("Runtime execution context shutdown failed: {}", contextError);
                LOG.debug("Runtime execution context shutdown failure details", error);
            }
        } else {
            LOG.debug("No RuntimeExecutionContext bean is available during shutdown.");
        }

        return new ShutdownResult(
                taskWorker != null,
                workerResult != null && workerResult.accepted(),
                workerResult != null && workerResult.completed(),
                workerResult,
                workerError,
                runtimeContext != null,
                contextResult != null && contextResult.shutdown(),
                contextResult != null && contextResult.terminated(),
                contextResult != null && contextResult.forced(),
                contextResult,
                contextError,
                Instant.now());
    }

    private static <T> T resolvable(Instance<T> instance) {
        return instance != null && instance.isResolvable() ? instance.get() : null;
    }

    private static String errorSummary(Throwable error) {
        if (error == null) {
            return null;
        }
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            return error.getClass().getSimpleName();
        }
        return error.getClass().getSimpleName() + ": " + message.replaceAll("\\s+", " ").trim();
    }

    record ShutdownResult(
            boolean taskWorkerAvailable,
            boolean taskWorkerDrainAccepted,
            boolean taskWorkerDrainCompleted,
            WorkerControlResult taskWorkerResult,
            String taskWorkerError,
            boolean runtimeContextAvailable,
            boolean runtimeContextShutdown,
            boolean runtimeContextTerminated,
            boolean runtimeContextForced,
            RuntimeShutdownResult runtimeContextResult,
            String runtimeContextError,
            Instant observedAt) {
    }
}
