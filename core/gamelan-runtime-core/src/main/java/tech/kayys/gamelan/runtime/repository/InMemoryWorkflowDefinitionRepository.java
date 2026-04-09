package tech.kayys.gamelan.runtime.repository;

import io.quarkus.arc.DefaultBean;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import tech.kayys.gamelan.engine.repository.WorkflowDefinitionRepository;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinition;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinitionId;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
@DefaultBean
public class InMemoryWorkflowDefinitionRepository implements WorkflowDefinitionRepository {

    private record DefinitionKey(String tenantId, String definitionId) {}

    private final Map<DefinitionKey, WorkflowDefinition> definitions = new ConcurrentHashMap<>();

    private DefinitionKey key(WorkflowDefinitionId id, TenantId tenantId) {
        return new DefinitionKey(tenantId.value(), id.value());
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
    public Uni<WorkflowDefinition> findByName(String name, TenantId tenantId) {
        return Uni.createFrom().item(definitions.values().stream()
                .filter(d -> d.tenantId().equals(tenantId) && d.name().equals(name))
                .findFirst()
                .orElse(null));
    }

    @Override
    public Uni<Void> delete(WorkflowDefinitionId id, TenantId tenantId) {
        definitions.remove(key(id, tenantId));
        return Uni.createFrom().voidItem();
    }
}
