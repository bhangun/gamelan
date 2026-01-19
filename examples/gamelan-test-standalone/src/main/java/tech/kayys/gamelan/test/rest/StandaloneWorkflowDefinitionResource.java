package tech.kayys.gamelan.test.rest;

import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import tech.kayys.gamelan.api.workflow.WorkflowDefinitionService;
import tech.kayys.gamelan.dto.CreateWorkflowDefinitionRequest;
import tech.kayys.gamelan.dto.UpdateWorkflowDefinitionRequest;
import tech.kayys.gamelan.model.TenantId;
import tech.kayys.gamelan.model.WorkflowDefinition;
import tech.kayys.gamelan.model.WorkflowDefinitionId;
import tech.kayys.gamelan.security.TenantSecurityContext;
import java.util.List;

@Path("/api/v1/workflow-definitions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class StandaloneWorkflowDefinitionResource {

    @Inject
    WorkflowDefinitionService engineService;

    @Inject
    TenantSecurityContext securityContext;

    @POST
    public Uni<WorkflowDefinition> create(CreateWorkflowDefinitionRequest request) {
        TenantId tenantId = securityContext.getCurrentTenant();
        return engineService.create(request, tenantId);
    }

    @GET
    @Path("/{id}")
    public Uni<WorkflowDefinition> get(@PathParam("id") String id) {
        TenantId tenantId = securityContext.getCurrentTenant();
        return engineService.get(new WorkflowDefinitionId(id), tenantId);
    }

    @GET
    public Uni<List<WorkflowDefinition>> list(@QueryParam("activeOnly") boolean activeOnly) {
        TenantId tenantId = securityContext.getCurrentTenant();
        return engineService.list(tenantId, activeOnly);
    }

    @PUT
    @Path("/{id}")
    public Uni<WorkflowDefinition> update(@PathParam("id") String id, UpdateWorkflowDefinitionRequest request) {
        TenantId tenantId = securityContext.getCurrentTenant();
        return engineService.update(new WorkflowDefinitionId(id), request, tenantId);
    }

    @DELETE
    @Path("/{id}")
    public Uni<Void> delete(@PathParam("id") String id) {
        TenantId tenantId = securityContext.getCurrentTenant();
        return engineService.delete(new WorkflowDefinitionId(id), tenantId);
    }
}
