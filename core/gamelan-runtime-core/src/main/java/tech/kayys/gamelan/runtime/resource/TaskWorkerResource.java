package tech.kayys.gamelan.runtime.resource;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import tech.kayys.gamelan.scheduler.TaskWorker;

@Path("/api/v1/task-worker")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class TaskWorkerResource {

    @Inject
    TaskWorker taskWorker;

    @GET
    @Path("/status")
    public TaskWorker.WorkerStatus status() {
        return taskWorker.status();
    }

    @POST
    @Path("/pause")
    public Response pause() {
        return controlResponse(taskWorker.pause());
    }

    @POST
    @Path("/drain")
    public Response drain() {
        return controlResponse(taskWorker.drain());
    }

    @POST
    @Path("/resume")
    public Response resume() {
        return controlResponse(taskWorker.resume());
    }

    private static Response controlResponse(TaskWorker.WorkerControlResult result) {
        if (!result.accepted()) {
            return Response.status(Response.Status.CONFLICT).entity(result).build();
        }
        if (!result.completed()) {
            return Response.status(Response.Status.ACCEPTED).entity(result).build();
        }
        return Response.ok(result).build();
    }
}
