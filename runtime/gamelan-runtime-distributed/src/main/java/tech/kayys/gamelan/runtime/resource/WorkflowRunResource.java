package tech.kayys.gamelan.runtime.resource;

import java.util.List;
import java.util.Map;

import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import tech.kayys.gamelan.engine.workflow.WorkflowRunManager;
import tech.kayys.gamelan.engine.execution.ExecutionHistory;
import tech.kayys.gamelan.engine.run.CreateRunRequest;
import tech.kayys.gamelan.engine.run.RunStatus;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinitionId;
import tech.kayys.gamelan.engine.workflow.WorkflowRun;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunSnapshot;
import tech.kayys.gamelan.security.TenantSecurityContext;

@Path("/api/v1/workflow-runs")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class WorkflowRunResource {

    @Inject
    WorkflowRunManager runManager;

    @Inject
    TenantSecurityContext securityContext;

    @POST
    public Uni<WorkflowRun> create(CreateRunRequest request) {
        TenantId tenantId = securityContext.getCurrentTenant();
        return runManager.createRun(request, tenantId)
                .flatMap(run -> {
                    if (request.isAutoStart()) {
                        return runManager.startRun(run.getId(), tenantId);
                    }
                    return Uni.createFrom().item(run);
                });
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
        // nodeId is optional for manual suspension
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
