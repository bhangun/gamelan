package tech.kayys.silat.workflow;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import tech.kayys.silat.model.TenantId;
import tech.kayys.silat.model.WorkflowDefinitionId;
import tech.kayys.silat.dto.CreateWorkflowDefinitionRequest;
import tech.kayys.silat.dto.UpdateWorkflowDefinitionRequest;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WorkflowDefinitionServiceTest {

    @InjectMocks
    WorkflowDefinitionService service;

    @Mock
    WorkflowDefinitionRegistry mockRegistry;

    @BeforeEach
    void setUp() {
        // Mocks initialized by MockitoExtension
    }

    @Test
    void create_whenCalled_returnsNullForNow() {
        // Arrange
        CreateWorkflowDefinitionRequest request = mock(CreateWorkflowDefinitionRequest.class);
        when(request.name()).thenReturn("test-workflow");
        when(request.version()).thenReturn("1.0.0");

        TenantId tenantId = new TenantId("tenant1");

        // Act
        var result = service.create(request, tenantId)
                .await().indefinitely();

        // Assert
        assertNull(result); // Currently returns null as per implementation
    }

    @Test
    void list_whenCalled_returnsEmptyList() {
        // Arrange
        TenantId tenantId = new TenantId("tenant1");

        // Act
        var result = service.list(tenantId, true)
                .await().indefinitely();

        // Assert
        assertNotNull(result);
        assertTrue(result.isEmpty()); // Currently returns empty list as per implementation
    }

    @Test
    void update_whenCalled_returnsNullForNow() {
        // Arrange
        WorkflowDefinitionId id = new WorkflowDefinitionId("wf1");

        UpdateWorkflowDefinitionRequest request = mock(UpdateWorkflowDefinitionRequest.class);

        TenantId tenantId = new TenantId("tenant1");

        // Act
        var result = service.update(id, request, tenantId)
                .await().indefinitely();

        // Assert
        assertNull(result); // Currently returns null as per implementation
    }

    @Test
    void delete_whenCalled_returnsVoid() {
        // Arrange
        WorkflowDefinitionId id = new WorkflowDefinitionId("wf1");
        TenantId tenantId = new TenantId("tenant1");

        // Act & Assert
        assertDoesNotThrow(() -> {
            service.delete(id, tenantId)
                    .await().indefinitely();
        });
    }
}