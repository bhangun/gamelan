package tech.kayys.gamelan.test.rest;

import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import tech.kayys.gamelan.api.engine.WorkflowRunManager;
import tech.kayys.gamelan.execution.ExecutionHistory;
import tech.kayys.gamelan.model.*;
import tech.kayys.gamelan.security.TenantSecurityContext;
import java.util.List;
import java.util.Map;

@Path("/api/v1/workflow-runs")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class StandaloneWorkflowRunResource {

    @Inject
    WorkflowRunManager runManager;

    @Inject
    TenantSecurityContext securityContext;

    @POST
    public Uni<WorkflowRun> create(CreateRunRequest request) {
        System.out.println("StandaloneWorkflowRunResource SEVERE LOG: Received CreateRunRequest: " + request);
        System.out.println("StandaloneWorkflowRunResource SEVERE LOG: AutoStart value: " + request.isAutoStart());
        
        TenantId tenantId = securityContext.getCurrentTenant();
        return runManager.createRun(request, tenantId);
    }

    @GET
    @Path("/{id}")
    public Uni<WorkflowRun> get(@PathParam("id") String id) {
        TenantId tenantId = securityContext.getCurrentTenant();
        return runManager.getRun(WorkflowRunId.of(id), tenantId);
    }

    @GET
    @Path("/{id}/snapshot")
    public Uni<WorkflowRunSnapshot> getSnapshot(@PathParam("id") String id) {
        TenantId tenantId = securityContext.getCurrentTenant();
        return runManager.getSnapshot(WorkflowRunId.of(id), tenantId);
    }

    @GET
    @Path("/{id}/history")
    public Uni<ExecutionHistory> getHistory(@PathParam("id") String id) {
        TenantId tenantId = securityContext.getCurrentTenant();
        return runManager.getExecutionHistory(WorkflowRunId.of(id), tenantId);
    }

    @POST
    @Path("/{id}/start")
    public Uni<WorkflowRun> start(@PathParam("id") String id) {
        TenantId tenantId = securityContext.getCurrentTenant();
        return runManager.startRun(WorkflowRunId.of(id), tenantId);
    }

    @POST
    @Path("/{id}/suspend")
    public Uni<WorkflowRun> suspend(@PathParam("id") String id, Map<String, Object> params) {
        TenantId tenantId = securityContext.getCurrentTenant();
        String reason = (String) params.getOrDefault("reason", "Manual suspension");
        return runManager.suspendRun(WorkflowRunId.of(id), tenantId, reason, null);
    }

    @POST
    @Path("/{id}/resume")
    public Uni<WorkflowRun> resume(@PathParam("id") String id, Map<String, Object> resumeData) {
        TenantId tenantId = securityContext.getCurrentTenant();
        return runManager.resumeRun(WorkflowRunId.of(id), tenantId, resumeData);
    }

    @POST
    @Path("/{id}/cancel")
    public Uni<Void> cancel(@PathParam("id") String id, Map<String, Object> params) {
        TenantId tenantId = securityContext.getCurrentTenant();
        String reason = (String) params.getOrDefault("reason", "Manual cancellation");
        return runManager.cancelRun(WorkflowRunId.of(id), tenantId, reason);
    }

    @GET
    public Uni<List<WorkflowRun>> query(
            @QueryParam("definitionId") String definitionId,
            @QueryParam("status") RunStatus status,
            @QueryParam("page") @jakarta.ws.rs.DefaultValue("0") int page,
            @QueryParam("size") @jakarta.ws.rs.DefaultValue("10") int size) {
        TenantId tenantId = securityContext.getCurrentTenant();
        WorkflowDefinitionId wfDefId = definitionId != null ? new WorkflowDefinitionId(definitionId) : null;
        return runManager.queryRuns(tenantId, wfDefId, status, page, size);
    }
}
