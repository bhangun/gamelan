package tech.kayys.silat.test.rest;

import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import tech.kayys.silat.api.workflow.WorkflowDefinitionService;
import tech.kayys.silat.dto.CreateWorkflowDefinitionRequest;
import tech.kayys.silat.dto.UpdateWorkflowDefinitionRequest;
import tech.kayys.silat.model.TenantId;
import tech.kayys.silat.model.WorkflowDefinition;
import tech.kayys.silat.model.WorkflowDefinitionId;
import tech.kayys.silat.security.TenantSecurityContext;
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
