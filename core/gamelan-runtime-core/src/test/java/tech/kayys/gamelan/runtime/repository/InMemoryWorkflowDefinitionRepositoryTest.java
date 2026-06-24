package tech.kayys.gamelan.runtime.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tech.kayys.gamelan.engine.node.NodeDefinition;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.node.NodeType;
import tech.kayys.gamelan.engine.repository.WorkflowDefinitionRepository;
import tech.kayys.gamelan.engine.repository.contract.WorkflowDefinitionRepositoryContract;
import tech.kayys.gamelan.engine.run.RetryPolicy;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinition;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinitionId;

class InMemoryWorkflowDefinitionRepositoryTest implements WorkflowDefinitionRepositoryContract {

    private static final TenantId TENANT = TenantId.of("tenant-1");

    @Override
    public WorkflowDefinitionRepository newWorkflowDefinitionRepository() {
        return new InMemoryWorkflowDefinitionRepository();
    }

    @Test
    void delete_deactivatesWithoutRemovingDefinitionHistory() {
        InMemoryWorkflowDefinitionRepository repository = new InMemoryWorkflowDefinitionRepository();
        WorkflowDefinition definition = definition("wf-1", "orders");

        repository.save(definition, TENANT).await().indefinitely();
        repository.delete(definition.id(), TENANT).await().indefinitely();

        assertNull(repository.findById(definition.id(), TENANT).await().indefinitely());
        assertEquals(definition, repository.findByIdIncludingInactive(definition.id(), TENANT).await().indefinitely());
        assertEquals(List.of(), repository.findByTenant(TENANT, true).await().indefinitely());
        assertEquals(List.of(definition), repository.findByTenant(TENANT, false).await().indefinitely());
    }

    @Test
    void setActive_reactivatesDefinition() {
        InMemoryWorkflowDefinitionRepository repository = new InMemoryWorkflowDefinitionRepository();
        WorkflowDefinition definition = definition("wf-1", "orders");

        repository.save(definition, TENANT).await().indefinitely();
        repository.setActive(definition.id(), TENANT, false).await().indefinitely();
        repository.setActive(definition.id(), TENANT, true).await().indefinitely();

        assertEquals(definition, repository.findById(definition.id(), TENANT).await().indefinitely());
        assertEquals(definition, repository.findByName("orders", TENANT).await().indefinitely());
    }

    private static WorkflowDefinition definition(String id, String name) {
        return WorkflowDefinition.builder()
                .id(WorkflowDefinitionId.of(id))
                .tenantId(TENANT)
                .name(name)
                .version("1.0.0")
                .nodes(List.of(node("start")))
                .build();
    }

    private static NodeDefinition node(String id) {
        return new NodeDefinition(
                NodeId.of(id),
                id,
                NodeType.TASK,
                "local",
                Map.of(),
                List.of(),
                List.of(),
                RetryPolicy.none(),
                Duration.ZERO,
                false);
    }
}
