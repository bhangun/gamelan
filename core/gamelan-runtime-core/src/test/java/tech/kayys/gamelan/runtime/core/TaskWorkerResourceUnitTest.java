package tech.kayys.gamelan.runtime.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;

import org.junit.jupiter.api.Test;

import jakarta.ws.rs.core.Response;
import tech.kayys.gamelan.runtime.resource.TaskWorkerResource;
import tech.kayys.gamelan.scheduler.TaskWorker;

class TaskWorkerResourceUnitTest {

    @Test
    void statusDelegatesToTaskWorkerSnapshot() throws ReflectiveOperationException {
        TaskWorkerResource resource = new TaskWorkerResource();
        setTaskWorker(resource, new TaskWorker());

        TaskWorker.WorkerStatus status = resource.status();

        assertEquals(TaskWorker.WorkerState.STOPPED, status.state());
        assertFalse(status.running());
        assertFalse(status.operatorPaused());
        assertEquals("<unavailable>", status.queueImplementation());
        assertEquals("<unavailable>", status.dispatcherImplementation());
        assertNotNull(status.observedAt());
    }

    @Test
    void pauseReturnsAcceptedControlResult() throws ReflectiveOperationException {
        TaskWorkerResource resource = new TaskWorkerResource();
        setTaskWorker(resource, new TaskWorker());

        Response response = resource.pause();
        TaskWorker.WorkerControlResult result = (TaskWorker.WorkerControlResult) response.getEntity();

        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        assertEquals(TaskWorker.WorkerControlAction.PAUSE, result.action());
        assertTrue(result.accepted());
        assertTrue(result.completed());
        assertEquals("worker-paused", result.reason());
        assertEquals(TaskWorker.WorkerState.PAUSED, result.status().state());
    }

    @Test
    void resumeReturnsConflictWhenWorkerCannotResume() throws ReflectiveOperationException {
        TaskWorkerResource resource = new TaskWorkerResource();
        setTaskWorker(resource, new TaskWorker());

        Response response = resource.resume();
        TaskWorker.WorkerControlResult result = (TaskWorker.WorkerControlResult) response.getEntity();

        assertEquals(Response.Status.CONFLICT.getStatusCode(), response.getStatus());
        assertEquals(TaskWorker.WorkerControlAction.RESUME, result.action());
        assertFalse(result.accepted());
        assertEquals("task-queue-unavailable", result.reason());
    }

    private static void setTaskWorker(TaskWorkerResource resource, TaskWorker taskWorker)
            throws ReflectiveOperationException {
        Field field = TaskWorkerResource.class.getDeclaredField("taskWorker");
        field.setAccessible(true);
        field.set(resource, taskWorker);
    }
}
