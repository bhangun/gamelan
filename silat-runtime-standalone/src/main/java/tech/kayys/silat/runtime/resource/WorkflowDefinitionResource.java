package tech.kayys.silat.runtime.resource;

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
import tech.kayys.silat.dto.CreateWorkflowDefinitionRequest;
import tech.kayys.silat.dto.UpdateWorkflowDefinitionRequest;
import tech.kayys.silat.model.TenantId;
import tech.kayys.silat.model.WorkflowDefinition;
import tech.kayys.silat.model.WorkflowDefinitionId;
import tech.kayys.silat.runtime.workflow.RuntimeWorkflowDefinitionService;
import tech.kayys.silat.security.TenantSecurityContext;

@Path("/api/v1/workflow-definitions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class WorkflowDefinitionResource {

    @Inject
    RuntimeWorkflowDefinitionService service;

    @Inject
    TenantSecurityContext securityContext;

    @POST
    public Uni<WorkflowDefinition> create(CreateWorkflowDefinitionRequest request) {
        TenantId tenantId = securityContext.getCurrentTenant();
        return service.create(request, tenantId);
    }

    @GET
    @Path("/{id}")
    public Uni<WorkflowDefinition> get(@PathParam("id") String id) {
        TenantId tenantId = securityContext.getCurrentTenant();
        return service.get(new WorkflowDefinitionId(id), tenantId);
    }

    @GET
    public Uni<List<WorkflowDefinition>> list(@QueryParam("activeOnly") boolean activeOnly) {
        TenantId tenantId = securityContext.getCurrentTenant();
        return service.list(tenantId, activeOnly);
    }

    @PUT
    @Path("/{id}")
    public Uni<WorkflowDefinition> update(@PathParam("id") String id, UpdateWorkflowDefinitionRequest request) {
        TenantId tenantId = securityContext.getCurrentTenant();
        return service.update(new WorkflowDefinitionId(id), request, tenantId);
    }

    @DELETE
    @Path("/{id}")
    public Uni<Void> delete(@PathParam("id") String id) {
        TenantId tenantId = securityContext.getCurrentTenant();
        return service.delete(new WorkflowDefinitionId(id), tenantId);
    }
}
