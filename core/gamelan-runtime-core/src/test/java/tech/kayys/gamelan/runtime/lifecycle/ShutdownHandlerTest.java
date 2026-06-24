package tech.kayys.gamelan.runtime.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import jakarta.enterprise.inject.Instance;
import tech.kayys.gamelan.runtime.context.RuntimeExecutionContext;
import tech.kayys.gamelan.runtime.lifecycle.ShutdownHandler.ShutdownResult;
import tech.kayys.gamelan.scheduler.TaskWorker;

class ShutdownHandlerTest {

    @Test
    void shutdownRuntimeDrainsTaskWorkerAndStopsRuntimeContext() {
        ShutdownHandler handler = new ShutdownHandler();
        RecordingTaskWorker taskWorker = new RecordingTaskWorker();
        RuntimeExecutionContext runtimeContext = new RuntimeExecutionContext();
        handler.taskWorkers = instance(taskWorker);
        handler.runtimeContexts = instance(runtimeContext);

        ShutdownResult result = handler.shutdownRuntime();

        assertEquals(1, taskWorker.drainCalls);
        assertTrue(result.taskWorkerAvailable());
        assertTrue(result.taskWorkerDrainAccepted());
        assertTrue(result.taskWorkerDrainCompleted());
        assertEquals("worker-drained", result.taskWorkerResult().reason());
        assertNull(result.taskWorkerError());
        assertTrue(result.runtimeContextAvailable());
        assertTrue(result.runtimeContextShutdown());
        assertTrue(result.runtimeContextTerminated());
        assertEquals(false, result.runtimeContextForced());
        assertNotNull(result.runtimeContextResult());
        assertTrue(runtimeContext.getExecutorService().isShutdown());
        assertNotNull(result.observedAt());
    }

    @Test
    void shutdownRuntimeContinuesWhenTaskWorkerIsUnavailable() {
        ShutdownHandler handler = new ShutdownHandler();
        RuntimeExecutionContext runtimeContext = new RuntimeExecutionContext();
        handler.taskWorkers = unresolvedInstance();
        handler.runtimeContexts = instance(runtimeContext);

        ShutdownResult result = handler.shutdownRuntime();

        assertEquals(false, result.taskWorkerAvailable());
        assertEquals(false, result.taskWorkerDrainAccepted());
        assertEquals(false, result.taskWorkerDrainCompleted());
        assertNull(result.taskWorkerResult());
        assertTrue(result.runtimeContextAvailable());
        assertTrue(result.runtimeContextShutdown());
        assertTrue(result.runtimeContextTerminated());
        assertEquals(false, result.runtimeContextForced());
        assertNotNull(result.runtimeContextResult());
        assertTrue(runtimeContext.getExecutorService().isShutdown());
    }

    @Test
    void shutdownRuntimeReportsDrainTimeoutAsAcceptedButIncomplete() {
        ShutdownHandler handler = new ShutdownHandler();
        RecordingTaskWorker taskWorker = new RecordingTaskWorker(false, "drain-timeout");
        handler.taskWorkers = instance(taskWorker);
        handler.runtimeContexts = unresolvedInstance();

        ShutdownResult result = handler.shutdownRuntime();

        assertEquals(1, taskWorker.drainCalls);
        assertTrue(result.taskWorkerAvailable());
        assertTrue(result.taskWorkerDrainAccepted());
        assertEquals(false, result.taskWorkerDrainCompleted());
        assertEquals("drain-timeout", result.taskWorkerResult().reason());
        assertEquals(false, result.runtimeContextAvailable());
        assertEquals(false, result.runtimeContextShutdown());
        assertEquals(false, result.runtimeContextTerminated());
        assertEquals(false, result.runtimeContextForced());
        assertNull(result.runtimeContextResult());
    }

    @Test
    void shutdownRuntimeRecordsTaskWorkerDrainFailureAndStillStopsRuntimeContext() {
        ShutdownHandler handler = new ShutdownHandler();
        FailingTaskWorker taskWorker = new FailingTaskWorker();
        RuntimeExecutionContext runtimeContext = new RuntimeExecutionContext();
        handler.taskWorkers = instance(taskWorker);
        handler.runtimeContexts = instance(runtimeContext);

        ShutdownResult result = handler.shutdownRuntime();

        assertTrue(result.taskWorkerAvailable());
        assertEquals(false, result.taskWorkerDrainAccepted());
        assertEquals(false, result.taskWorkerDrainCompleted());
        assertNull(result.taskWorkerResult());
        assertEquals("IllegalStateException: drain failed", result.taskWorkerError());
        assertTrue(result.runtimeContextAvailable());
        assertTrue(result.runtimeContextShutdown());
        assertTrue(result.runtimeContextTerminated());
        assertEquals(false, result.runtimeContextForced());
        assertNotNull(result.runtimeContextResult());
        assertTrue(runtimeContext.getExecutorService().isShutdown());
    }

    private static <T> Instance<T> instance(T value) {
        @SuppressWarnings("unchecked")
        Instance<T> instance = mock(Instance.class);
        when(instance.isResolvable()).thenReturn(true);
        when(instance.get()).thenReturn(value);
        return instance;
    }

    private static <T> Instance<T> unresolvedInstance() {
        @SuppressWarnings("unchecked")
        Instance<T> instance = mock(Instance.class);
        when(instance.isResolvable()).thenReturn(false);
        return instance;
    }

    private static final class RecordingTaskWorker extends TaskWorker {
        private int drainCalls;
        private final boolean completed;
        private final String reason;

        private RecordingTaskWorker() {
            this(true, "worker-drained");
        }

        private RecordingTaskWorker(boolean completed, String reason) {
            this.completed = completed;
            this.reason = reason;
        }

        @Override
        public WorkerControlResult drain() {
            drainCalls++;
            return new WorkerControlResult(
                    WorkerControlAction.DRAIN,
                    true,
                    completed,
                    reason,
                    status(),
                    Instant.parse("2026-06-08T00:00:00Z"));
        }
    }

    private static final class FailingTaskWorker extends TaskWorker {
        @Override
        public WorkerControlResult drain() {
            throw new IllegalStateException("drain failed");
        }
    }
}
