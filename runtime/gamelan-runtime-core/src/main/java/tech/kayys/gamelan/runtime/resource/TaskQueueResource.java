package tech.kayys.gamelan.runtime.resource;

import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import tech.kayys.gamelan.scheduler.TaskQueue;

@Path("/api/v1/task-queue")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class TaskQueueResource {

    @Inject
    TaskQueue taskQueue;

    @GET
    @Path("/stats")
    public Uni<TaskQueue.QueueStats> stats() {
        return taskQueue.stats();
    }
}
