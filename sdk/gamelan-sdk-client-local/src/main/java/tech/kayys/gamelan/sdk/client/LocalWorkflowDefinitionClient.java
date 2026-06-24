package tech.kayys.gamelan.sdk.client;

import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinition;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinitionId;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinitionMapper;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinitionService;
import tech.kayys.gamelan.engine.workflow.dto.CreateWorkflowDefinitionRequest;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Local implementation of {@link WorkflowDefinitionClient} that directly calls the engine services.
 */
public class LocalWorkflowDefinitionClient implements WorkflowDefinitionClient {

    private final WorkflowDefinitionService definitionService;
    private final TenantId tenantId;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public LocalWorkflowDefinitionClient(WorkflowDefinitionService definitionService, String tenantId) {
        this.definitionService = definitionService;
        this.tenantId = TenantId.of(tenantId);
    }

    @Override
    public Uni<WorkflowDefinition> createWorkflow(WorkflowDefinition request) {
        checkClosed();
        CreateWorkflowDefinitionRequest dto = WorkflowDefinitionMapper.toCreateRequest(request);
        return definitionService().create(dto, tenantId);
    }

    @Override
    public Uni<WorkflowDefinition> getWorkflow(String workflowId) {
        checkClosed();
        return definitionService().get(WorkflowDefinitionId.of(workflowId), tenantId);
    }

    @Override
    public Uni<WorkflowDefinition> getWorkflowByName(String name) {
        checkClosed();
        return definitionService().getByName(name, tenantId);
    }

    @Override
    public Uni<List<WorkflowDefinition>> listWorkflows() {
        return listWorkflows(true);
    }

    @Override
    public Uni<List<WorkflowDefinition>> listWorkflows(boolean activeOnly) {
        checkClosed();
        return definitionService().list(tenantId, activeOnly);
    }

    @Override
    public Uni<Void> deleteWorkflow(String workflowId) {
        checkClosed();
        return definitionService().delete(WorkflowDefinitionId.of(workflowId), tenantId);
    }

    private WorkflowDefinitionService definitionService() {
        if (definitionService == null) {
            throw new IllegalStateException("WorkflowDefinitionService not provided for LOCAL transport");
        }
        return definitionService;
    }

    private void checkClosed() {
        if (closed.get()) {
            throw new IllegalStateException("Client is closed");
        }
    }

    @Override
    public void close() {
        closed.set(true);
    }
}
