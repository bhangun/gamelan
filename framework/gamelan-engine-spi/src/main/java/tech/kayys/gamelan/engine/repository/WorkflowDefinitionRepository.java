package tech.kayys.gamelan.engine.repository;

import java.util.List;

import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinition;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinitionId;

/**
 * Repository for workflow definitions
 */
public interface WorkflowDefinitionRepository {
    Uni<WorkflowDefinition> findById(WorkflowDefinitionId id, TenantId tenantId);

    default Uni<WorkflowDefinition> findByIdIncludingInactive(WorkflowDefinitionId id, TenantId tenantId) {
        return findById(id, tenantId);
    }

    Uni<WorkflowDefinition> save(WorkflowDefinition definition, TenantId tenantId);

    Uni<List<WorkflowDefinition>> findByTenant(TenantId tenantId, boolean activeOnly);

    Uni<WorkflowDefinition> findByName(String name, TenantId tenantId);

    default Uni<Void> setActive(WorkflowDefinitionId id, TenantId tenantId, boolean active) {
        return active ? Uni.createFrom().voidItem() : delete(id, tenantId);
    }

    Uni<Void> delete(WorkflowDefinitionId id, TenantId tenantId);
}
