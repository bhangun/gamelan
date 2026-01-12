package tech.kayys.silat.workflow;

import java.util.List;

import tech.kayys.silat.dto.CreateWorkflowDefinitionRequest;
import tech.kayys.silat.dto.UpdateWorkflowDefinitionRequest;
import tech.kayys.silat.model.TenantId;
import tech.kayys.silat.model.WorkflowDefinition;
import tech.kayys.silat.model.WorkflowDefinitionId;

/**
 * Workflow definition service
 */
@jakarta.enterprise.context.ApplicationScoped
public class WorkflowDefinitionService {

    @jakarta.inject.Inject
    WorkflowDefinitionRegistry registry;

    public io.smallrye.mutiny.Uni<WorkflowDefinition> create(
            CreateWorkflowDefinitionRequest request,
            TenantId tenantId) {
        // Convert DTO to domain object and register
        return io.smallrye.mutiny.Uni.createFrom().nullItem();
    }

    public io.smallrye.mutiny.Uni<WorkflowDefinition> get(
            WorkflowDefinitionId id,
            TenantId tenantId) {
        return registry.getDefinition(id, tenantId);
    }

    public io.smallrye.mutiny.Uni<List<WorkflowDefinition>> list(
            TenantId tenantId,
            boolean activeOnly) {
        return io.smallrye.mutiny.Uni.createFrom().item(List.of());
    }

    public io.smallrye.mutiny.Uni<WorkflowDefinition> update(
            WorkflowDefinitionId id,
            UpdateWorkflowDefinitionRequest request,
            TenantId tenantId) {
        return io.smallrye.mutiny.Uni.createFrom().nullItem();
    }

    public io.smallrye.mutiny.Uni<Void> delete(
            WorkflowDefinitionId id,
            TenantId tenantId) {
        return io.smallrye.mutiny.Uni.createFrom().voidItem();
    }
}