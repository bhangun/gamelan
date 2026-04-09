package tech.kayys.gamelan.runtime.resource;

import java.util.List;

import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import tech.kayys.gamelan.engine.config.GamelanConfig;
import tech.kayys.gamelan.engine.context.RequestContext;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinition;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinitionId;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinitionService;
import tech.kayys.gamelan.engine.workflow.dto.CreateWorkflowDefinitionRequest;
import tech.kayys.gamelan.engine.workflow.dto.UpdateWorkflowDefinitionRequest;

@Path("/api/v1/workflow-definitions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class WorkflowDefinitionResource {

    @Inject
    WorkflowDefinitionService service;

    @Inject
    RequestContext requestContext;

    @Inject
    GamelanConfig config;

    private TenantId tenant() {
        return requestContext.getTenantId().orElseGet(config::getDefaultTenant);
    }

    @POST
    public Uni<WorkflowDefinition> create(CreateWorkflowDefinitionRequest request) {
        return service.create(request, tenant());
    }

    @GET
    @Path("/{id}")
    public Uni<WorkflowDefinition> get(@PathParam("id") String id) {
        return service.get(new WorkflowDefinitionId(id), tenant());
    }

    @GET
    public Uni<List<WorkflowDefinition>> list(@QueryParam("activeOnly") boolean activeOnly) {
        return service.list(tenant(), activeOnly);
    }

    @PUT
    @Path("/{id}")
    public Uni<WorkflowDefinition> update(@PathParam("id") String id, UpdateWorkflowDefinitionRequest request) {
        return service.update(new WorkflowDefinitionId(id), request, tenant());
    }

    @DELETE
    @Path("/{id}")
    public Uni<Void> delete(@PathParam("id") String id) {
        return service.delete(new WorkflowDefinitionId(id), tenant());
    }
}
