package tech.kayys.gamelan.engine.repository.contract;

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
import tech.kayys.gamelan.engine.run.RetryPolicy;
import tech.kayys.gamelan.engine.saga.CompensationPolicy;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinition;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinitionId;
import tech.kayys.gamelan.engine.workflow.WorkflowMode;

/**
 * Shared conformance tests for workflow-definition persistence and activation lifecycle.
 */
public interface WorkflowDefinitionRepositoryContract {

    TenantId CONTRACT_TENANT = TenantId.of("contract-definition-tenant");
    TenantId OTHER_TENANT = TenantId.of("contract-definition-other-tenant");

    WorkflowDefinitionRepository newWorkflowDefinitionRepository();

    @Test
    default void workflowDefinitionRepositoryContract_savesAndFindsDefinitionsByTenantIdAndName() {
        WorkflowDefinitionRepository repository = newWorkflowDefinitionRepository();
        WorkflowDefinition tenantDefinition = definition(
                "contract-shared-definition",
                "shared-definition-name",
                CONTRACT_TENANT);
        WorkflowDefinition otherTenantDefinition = definition(
                "contract-shared-definition",
                "shared-definition-name",
                OTHER_TENANT);

        repository.save(tenantDefinition, CONTRACT_TENANT).await().indefinitely();
        repository.save(otherTenantDefinition, OTHER_TENANT).await().indefinitely();

        assertEquals(tenantDefinition, repository.findById(tenantDefinition.id(), CONTRACT_TENANT)
                .await()
                .indefinitely());
        assertEquals(otherTenantDefinition, repository.findById(otherTenantDefinition.id(), OTHER_TENANT)
                .await()
                .indefinitely());
        assertEquals(tenantDefinition, repository.findByName("shared-definition-name", CONTRACT_TENANT)
                .await()
                .indefinitely());
        assertEquals(otherTenantDefinition, repository.findByName("shared-definition-name", OTHER_TENANT)
                .await()
                .indefinitely());
        assertEquals(List.of(tenantDefinition), repository.findByTenant(CONTRACT_TENANT, true)
                .await()
                .indefinitely());
        assertEquals(List.of(otherTenantDefinition), repository.findByTenant(OTHER_TENANT, true)
                .await()
                .indefinitely());
    }

    @Test
    default void workflowDefinitionRepositoryContract_deactivationPreservesInactiveHistoryAndCanReactivate() {
        WorkflowDefinitionRepository repository = newWorkflowDefinitionRepository();
        WorkflowDefinition definition = definition(
                "contract-definition-active-state",
                "definition-active-state",
                CONTRACT_TENANT);

        repository.save(definition, CONTRACT_TENANT).await().indefinitely();
        repository.delete(definition.id(), CONTRACT_TENANT).await().indefinitely();

        assertNull(repository.findById(definition.id(), CONTRACT_TENANT).await().indefinitely());
        assertNull(repository.findByName(definition.name(), CONTRACT_TENANT).await().indefinitely());
        assertEquals(definition, repository.findByIdIncludingInactive(definition.id(), CONTRACT_TENANT)
                .await()
                .indefinitely());
        assertEquals(List.of(), repository.findByTenant(CONTRACT_TENANT, true).await().indefinitely());
        assertEquals(List.of(definition), repository.findByTenant(CONTRACT_TENANT, false).await().indefinitely());

        repository.setActive(definition.id(), CONTRACT_TENANT, true).await().indefinitely();

        assertEquals(definition, repository.findById(definition.id(), CONTRACT_TENANT).await().indefinitely());
        assertEquals(definition, repository.findByName(definition.name(), CONTRACT_TENANT).await().indefinitely());
        assertEquals(List.of(definition), repository.findByTenant(CONTRACT_TENANT, true).await().indefinitely());
    }

    @Test
    default void workflowDefinitionRepositoryContract_saveReactivatesInactiveDefinitions() {
        WorkflowDefinitionRepository repository = newWorkflowDefinitionRepository();
        WorkflowDefinition definition = definition(
                "contract-definition-save-reactivates",
                "save-reactivates",
                CONTRACT_TENANT);

        repository.save(definition, CONTRACT_TENANT).await().indefinitely();
        repository.setActive(definition.id(), CONTRACT_TENANT, false).await().indefinitely();
        repository.save(definition, CONTRACT_TENANT).await().indefinitely();

        assertEquals(definition, repository.findById(definition.id(), CONTRACT_TENANT).await().indefinitely());
        assertEquals(List.of(definition), repository.findByTenant(CONTRACT_TENANT, true).await().indefinitely());
    }

    private static WorkflowDefinition definition(String id, String name, TenantId tenantId) {
        return new WorkflowDefinition(
                WorkflowDefinitionId.of(id),
                tenantId,
                name,
                "1.0.0",
                null,
                WorkflowMode.FLOW,
                List.of(node()),
                Map.of(),
                Map.of(),
                null,
                RetryPolicy.none(),
                CompensationPolicy.disabled());
    }

    private static NodeDefinition node() {
        return new NodeDefinition(
                NodeId.of("contract-definition-node"),
                "contract definition node",
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
