package tech.kayys.gamelan.engine.workflow;

import java.util.List;

import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.dto.CreateWorkflowDefinitionRequest;
import tech.kayys.gamelan.engine.workflow.dto.UpdateWorkflowDefinitionRequest;

/**
 * Workflow definition service interface
 */
public interface WorkflowDefinitionService {

        Uni<WorkflowDefinition> create(
                        CreateWorkflowDefinitionRequest request,
                        TenantId tenantId);

        Uni<WorkflowDefinition> get(
                        WorkflowDefinitionId id,
                        TenantId tenantId);

        Uni<List<WorkflowDefinition>> list(
                        TenantId tenantId,
                        boolean activeOnly);

        Uni<WorkflowDefinition> update(
                        WorkflowDefinitionId id,
                        UpdateWorkflowDefinitionRequest request,
                        TenantId tenantId);

        Uni<Void> delete(
                        WorkflowDefinitionId id,
                        TenantId tenantId);
}