package tech.kayys.gamelan.sdk.client;

import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinition;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinitionService;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinitionId;
import tech.kayys.gamelan.engine.tenant.TenantId;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Local-based implementation of {@link WorkflowDefinitionClient}.
 * This transport allows direct interaction with the engine service within the
 * same JVM.
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
        if (definitionService == null) {
            return Uni.createFrom()
                    .failure(new IllegalStateException("WorkflowDefinitionService not provided for LOCAL transport"));
        }
        return Uni.createFrom().failure(
                new UnsupportedOperationException("Mapping domain to DTO not implemented for LOCAL create yet"));
    }

    @Override
    public Uni<WorkflowDefinition> getWorkflow(String definitionId) {
        checkClosed();
        return definitionService.get(WorkflowDefinitionId.of(definitionId), tenantId);
    }

    @Override
    public Uni<WorkflowDefinition> getWorkflowByName(String name) {
        checkClosed();
        return definitionService.getByName(name, tenantId);
    }

    @Override
    public Uni<List<WorkflowDefinition>> listWorkflows() {
        checkClosed();
        return definitionService.list(tenantId, true);
    }

    @Override
    public Uni<Void> deleteWorkflow(String definitionId) {
        checkClosed();
        return definitionService.delete(WorkflowDefinitionId.of(definitionId), tenantId);
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
