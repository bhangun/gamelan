package tech.kayys.gamelan.test.repository;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.annotation.Priority;
import tech.kayys.gamelan.repository.WorkflowDefinitionRepository;
import tech.kayys.gamelan.model.TenantId;
import tech.kayys.gamelan.model.WorkflowDefinition;
import tech.kayys.gamelan.model.WorkflowDefinitionId;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Alternative
@Priority(1)
@ApplicationScoped
public class InMemoryWorkflowDefinitionRepository implements WorkflowDefinitionRepository {

    private final Map<String, WorkflowDefinition> definitions = new ConcurrentHashMap<>();

    private String key(WorkflowDefinitionId id, TenantId tenantId) {
        return tenantId.value() + ":" + id.value();
    }

    @Override
    public Uni<WorkflowDefinition> findById(WorkflowDefinitionId id, TenantId tenantId) {
        return Uni.createFrom().item(definitions.get(key(id, tenantId)));
    }

    @Override
    public Uni<WorkflowDefinition> save(WorkflowDefinition definition, TenantId tenantId) {
        definitions.put(key(definition.id(), tenantId), definition);
        return Uni.createFrom().item(definition);
    }

    @Override
    public Uni<List<WorkflowDefinition>> findByTenant(TenantId tenantId, boolean activeOnly) {
        return Uni.createFrom().item(definitions.values().stream()
                .filter(d -> d.tenantId().equals(tenantId))
                .toList());
    }

    @Override
    public Uni<Void> delete(WorkflowDefinitionId id, TenantId tenantId) {
        definitions.remove(key(id, tenantId));
        return Uni.createFrom().voidItem();
    }
}
