package tech.kayys.gamelan.sdk.client;

import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinition;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinitionId;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinitionMapper;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinitionService;
import tech.kayys.gamelan.engine.workflow.dto.CreateWorkflowDefinitionRequest;

import java.util.List;

/**
 * Local implementation of {@link WorkflowDefinitionClient} that directly calls the engine services.
 */
public class LocalWorkflowDefinitionClient implements WorkflowDefinitionClient {

    private final WorkflowDefinitionService definitionService;
    private final TenantId tenantId;

    public LocalWorkflowDefinitionClient(WorkflowDefinitionService definitionService, String tenantId) {
        this.definitionService = definitionService;
        this.tenantId = TenantId.of(tenantId);
    }

    @Override
    public Uni<WorkflowDefinition> createWorkflow(WorkflowDefinition request) {
        CreateWorkflowDefinitionRequest dto = WorkflowDefinitionMapper.toCreateRequest(request);
        return definitionService.create(dto, tenantId);
    }

    @Override
    public Uni<WorkflowDefinition> getWorkflow(String workflowId) {
        return definitionService.get(WorkflowDefinitionId.of(workflowId), tenantId);
    }

    @Override
    public Uni<WorkflowDefinition> getWorkflowByName(String name) {
        return Uni.createFrom().failure(new UnsupportedOperationException("getWorkflowByName not implemented in local client yet"));
    }

    @Override
    public Uni<List<WorkflowDefinition>> listWorkflows() {
        return definitionService.list(tenantId, false);
    }

    @Override
    public Uni<Void> deleteWorkflow(String workflowId) {
        return definitionService.delete(WorkflowDefinitionId.of(workflowId), tenantId);
    }

    @Override
    public void close() {
        // No-op for local client as it doesn't own the definitionService
    }
}
