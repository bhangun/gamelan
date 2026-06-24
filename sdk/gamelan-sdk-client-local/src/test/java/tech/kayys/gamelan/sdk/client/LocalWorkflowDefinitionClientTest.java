package tech.kayys.gamelan.sdk.client;

import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.Test;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinition;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinitionId;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinitionService;
import tech.kayys.gamelan.engine.workflow.WorkflowMetadata;
import tech.kayys.gamelan.engine.workflow.WorkflowMode;
import tech.kayys.gamelan.engine.workflow.dto.CreateWorkflowDefinitionRequest;
import tech.kayys.gamelan.engine.workflow.dto.UpdateWorkflowDefinitionRequest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalWorkflowDefinitionClientTest {

    private static final TenantId TENANT = TenantId.of("tenant-local");

    @Test
    void createWorkflowDelegatesMappedDefinitionToLocalService() {
        CapturingDefinitionService service = new CapturingDefinitionService();
        LocalWorkflowDefinitionClient client = new LocalWorkflowDefinitionClient(service, TENANT.value());
        WorkflowDefinition definition = definition("wf-1", "offline-agent-workflow");

        WorkflowDefinition response = client.createWorkflow(definition).await().indefinitely();

        assertSame(service.definition, response);
        assertEquals(TENANT, service.createTenant);
        assertEquals("offline-agent-workflow", service.createRequest.name());
        assertEquals("1.0.0", service.createRequest.version());
        assertEquals("local file-backed workflow", service.createRequest.description());
        assertEquals("file", service.createRequest.metadata().get("persistence"));
    }

    @Test
    void getByNameListAndDeleteUseTenantScopedLocalService() {
        CapturingDefinitionService service = new CapturingDefinitionService();
        LocalWorkflowDefinitionClient client = new LocalWorkflowDefinitionClient(service, TENANT.value());

        assertSame(service.definition, client.getWorkflowByName("offline-agent-workflow").await().indefinitely());
        assertEquals("offline-agent-workflow", service.name);
        assertEquals(TENANT, service.nameTenant);

        assertEquals(1, client.listWorkflows().await().indefinitely().size());
        assertEquals(TENANT, service.listTenant);
        assertTrue(service.activeOnly);

        assertEquals(1, client.listWorkflows(false).await().indefinitely().size());
        assertFalse(service.activeOnly);

        client.deleteWorkflow("wf-1").await().indefinitely();
        assertEquals(WorkflowDefinitionId.of("wf-1"), service.deleteId);
        assertEquals(TENANT, service.deleteTenant);
    }

    @Test
    void closeStateRejectsCallsSynchronously() {
        LocalWorkflowDefinitionClient client =
                new LocalWorkflowDefinitionClient(new CapturingDefinitionService(), TENANT.value());

        client.close();

        assertThrows(IllegalStateException.class, () -> client.getWorkflow("wf-1"));
    }

    @Test
    void missingDefinitionServiceFailsWithExplicitLocalTransportError() {
        LocalWorkflowDefinitionClient client = new LocalWorkflowDefinitionClient(null, TENANT.value());

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> client.listWorkflows());

        assertEquals("WorkflowDefinitionService not provided for LOCAL transport", error.getMessage());
    }

    private static WorkflowDefinition definition(String id, String name) {
        return new WorkflowDefinition(
                WorkflowDefinitionId.of(id),
                TenantId.of("definition-tenant"),
                name,
                "1.0.0",
                "local file-backed workflow",
                WorkflowMode.FLOW,
                List.of(),
                Map.of(),
                Map.of(),
                WorkflowMetadata.system(Map.of("persistence", "file")),
                null,
                null);
    }

    private static final class CapturingDefinitionService implements WorkflowDefinitionService {
        private final WorkflowDefinition definition = definition("created-wf", "offline-agent-workflow");
        private CreateWorkflowDefinitionRequest createRequest;
        private TenantId createTenant;
        private WorkflowDefinitionId getId;
        private TenantId getTenant;
        private String name;
        private TenantId nameTenant;
        private TenantId listTenant;
        private boolean activeOnly;
        private WorkflowDefinitionId deleteId;
        private TenantId deleteTenant;

        @Override
        public Uni<WorkflowDefinition> create(CreateWorkflowDefinitionRequest request, TenantId tenantId) {
            createRequest = request;
            createTenant = tenantId;
            return Uni.createFrom().item(definition);
        }

        @Override
        public Uni<WorkflowDefinition> get(WorkflowDefinitionId id, TenantId tenantId) {
            getId = id;
            getTenant = tenantId;
            return Uni.createFrom().item(definition);
        }

        @Override
        public Uni<List<WorkflowDefinition>> list(TenantId tenantId, boolean activeOnly) {
            listTenant = tenantId;
            this.activeOnly = activeOnly;
            return Uni.createFrom().item(List.of(definition));
        }

        @Override
        public Uni<WorkflowDefinition> getByName(String name, TenantId tenantId) {
            this.name = name;
            nameTenant = tenantId;
            return Uni.createFrom().item(definition);
        }

        @Override
        public Uni<WorkflowDefinition> update(
                WorkflowDefinitionId id,
                UpdateWorkflowDefinitionRequest request,
                TenantId tenantId) {
            return Uni.createFrom().item(definition);
        }

        @Override
        public Uni<Void> delete(WorkflowDefinitionId id, TenantId tenantId) {
            deleteId = id;
            deleteTenant = tenantId;
            return Uni.createFrom().voidItem();
        }
    }
}
