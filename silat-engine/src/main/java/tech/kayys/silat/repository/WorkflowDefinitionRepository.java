package tech.kayys.silat.repository;

import java.util.List;

import io.smallrye.mutiny.Uni;
import tech.kayys.silat.model.TenantId;
import tech.kayys.silat.model.WorkflowDefinition;
import tech.kayys.silat.model.WorkflowDefinitionId;

/**
 * Repository for workflow definitions
 */
public interface WorkflowDefinitionRepository {
    Uni<WorkflowDefinition> findById(WorkflowDefinitionId id, TenantId tenantId);

    Uni<WorkflowDefinition> save(WorkflowDefinition definition, TenantId tenantId);

    Uni<List<WorkflowDefinition>> findByTenant(TenantId tenantId, boolean activeOnly);

    Uni<Void> delete(WorkflowDefinitionId id, TenantId tenantId);
}
