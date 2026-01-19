package tech.kayys.gamelan.runtime.resource;

import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import tech.kayys.gamelan.engine.workflow.WorkflowRunManager;
import tech.kayys.gamelan.engine.signal.ExternalSignal;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;

@Path("/api/v1/callbacks")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CallbackResource {

    @Inject
    WorkflowRunManager runManager;

    @POST
    @Path("/{runId}/signal")
    public Uni<Void> signal(
            @PathParam("runId") String runId,
            @QueryParam("token") String callbackToken,
            ExternalSignal signal) {
        return runManager.onExternalSignal(WorkflowRunId.of(runId), signal, callbackToken);
    }
}
