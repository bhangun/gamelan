package tech.kayys.gamelan.runtime.workflow;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import tech.kayys.gamelan.dto.CreateWorkflowDefinitionRequest;
import tech.kayys.gamelan.dto.UpdateWorkflowDefinitionRequest;
import tech.kayys.gamelan.model.TenantId;
import tech.kayys.gamelan.model.WorkflowDefinition;
import tech.kayys.gamelan.model.WorkflowDefinitionId;
import java.util.List;

/**
 * Runtime service that acts as an adapter between API layer and engine layer
 */
@ApplicationScoped
public class RuntimeWorkflowDefinitionService {

    @Inject
    tech.kayys.gamelan.api.workflow.WorkflowDefinitionService engineService;

    public Uni<WorkflowDefinition> create(
            CreateWorkflowDefinitionRequest request,
            TenantId tenantId) {
        // The engine service now accepts DTOs directly since it implements the API
        // interface
        return engineService.create(request, tenantId);
    }

    public Uni<WorkflowDefinition> get(
            WorkflowDefinitionId id,
            TenantId tenantId) {
        return engineService.get(id, tenantId);
    }

    public Uni<List<WorkflowDefinition>> list(
            TenantId tenantId,
            boolean activeOnly) {
        return engineService.list(tenantId, activeOnly);
    }

    public Uni<WorkflowDefinition> update(
            WorkflowDefinitionId id,
            UpdateWorkflowDefinitionRequest request,
            TenantId tenantId) {
        // The engine service now accepts DTOs directly since it implements the API
        // interface
        return engineService.update(id, request, tenantId);
    }

    public Uni<Void> delete(
            WorkflowDefinitionId id,
            TenantId tenantId) {
        return engineService.delete(id, tenantId);
    }
}