package tech.kayys.gamelan.sdk.client;

import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinitionService;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinition;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinitionId;
import tech.kayys.gamelan.engine.tenant.TenantId;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class LocalWorkflowDefinitionClientTest {

    @Mock
    private WorkflowDefinitionService definitionService;

    @Mock
    private WorkflowDefinition mockDefinition;

    private LocalWorkflowDefinitionClient client;
    private final String tenantId = "test-tenant";

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        client = new LocalWorkflowDefinitionClient(definitionService, tenantId);

        when(mockDefinition.id()).thenReturn(WorkflowDefinitionId.of("wf-1"));
        when(mockDefinition.name()).thenReturn("Workflow 1");
    }

    @Test
    void testGetDefinitionDelegate() {
        String defId = "wf-1";
        when(definitionService.get(any(), any())).thenReturn(Uni.createFrom().item(mockDefinition));

        WorkflowDefinition response = client.getDefinition(defId).await().indefinitely();

        assertNotNull(response);
        verify(definitionService).get(eq(WorkflowDefinitionId.of(defId)), eq(TenantId.of(tenantId)));
    }

    @Test
    void testListDefinitionsDelegate() {
        when(definitionService.list(any(), anyBoolean()))
                .thenReturn(Uni.createFrom().item(List.of(mockDefinition)));

        List<WorkflowDefinition> response = client.listDefinitions(true).await().indefinitely();

        assertNotNull(response);
        assertEquals(1, response.size());
        verify(definitionService).list(eq(TenantId.of(tenantId)), eq(true));
    }

    @Test
    void testCloseState() {
        client.close();
        assertThrows(IllegalStateException.class, () -> client.getDefinition("any"));
    }
}
