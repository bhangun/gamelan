package tech.kayys.gamelan.test.client.mock;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.engine.repository.WorkflowDefinitionRepository;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinition;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinitionId;

@ApplicationScoped
@Alternative
@Priority(1)
public class InMemoryWorkflowDefinitionRepository implements WorkflowDefinitionRepository {

    private final Map<WorkflowDefinitionId, WorkflowDefinition> definitions = new ConcurrentHashMap<>();

    @Override
    public Uni<WorkflowDefinition> findById(WorkflowDefinitionId id, TenantId tenantId) {
        return Uni.createFrom().item(definitions.get(id));
    }

    @Override
    public Uni<WorkflowDefinition> save(WorkflowDefinition definition, TenantId tenantId) {
        definitions.put(definition.id(), definition);
        return Uni.createFrom().item(definition);
    }

    @Override
    public Uni<List<WorkflowDefinition>> findByTenant(TenantId tenantId, boolean activeOnly) {
        return Uni.createFrom().item(Collections.emptyList());
    }

    @Override
    public Uni<WorkflowDefinition> findByName(String name, TenantId tenantId) {
        return Uni.createFrom().item(definitions.values().stream()
                .filter(d -> d.name().equals(name))
                .findFirst()
                .orElse(null));
    }

    @Override
    public Uni<Void> delete(WorkflowDefinitionId id, TenantId tenantId) {
        definitions.remove(id);
        return Uni.createFrom().voidItem();
    }
}
