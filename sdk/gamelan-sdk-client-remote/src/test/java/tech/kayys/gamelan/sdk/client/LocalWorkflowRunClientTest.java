package tech.kayys.gamelan.sdk.client;

import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import tech.kayys.gamelan.engine.workflow.WorkflowRunManager;
import tech.kayys.gamelan.engine.workflow.WorkflowRun;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.run.RunResponse;
import tech.kayys.gamelan.engine.run.RunStatus;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LocalWorkflowRunClientTest {

    @Mock
    private WorkflowRunManager runManager;

    @Mock
    private WorkflowRun mockRun;

    private LocalWorkflowRunClient client;
    private final String tenantId = "test-tenant";

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        client = new LocalWorkflowRunClient(runManager, tenantId);

        when(mockRun.id()).thenReturn(WorkflowRunId.of(UUID.randomUUID().toString()));
        when(mockRun.status()).thenReturn(RunStatus.CREATED);
    }

    @Test
    void testCreateRunDelegate() {
        tech.kayys.gamelan.engine.run.CreateRunRequest request = new tech.kayys.gamelan.engine.run.CreateRunRequest(
                "wf-1", null, Map.of(), null, false);

        when(runManager.createRun(any(), any())).thenReturn(Uni.createFrom().item(mockRun));

        RunResponse response = client.createRun(request).await().indefinitely();

        assertNotNull(response);
        verify(runManager).createRun(eq(request), eq(TenantId.of(tenantId)));
    }

    @Test
    void testGetRunDelegate() {
        String runId = UUID.randomUUID().toString();
        when(runManager.getRun(any(), any())).thenReturn(Uni.createFrom().item(mockRun));

        RunResponse response = client.getRun(runId).await().indefinitely();

        assertNotNull(response);
        verify(runManager).getRun(eq(WorkflowRunId.of(runId)), eq(TenantId.of(tenantId)));
    }

    @Test
    void testResumeRunDelegate() {
        String runId = UUID.randomUUID().toString();
        Map<String, Object> data = Map.of("key", "val");
        String humanTaskId = "task-123";

        when(runManager.resumeRun(any(), any(), any(), any())).thenReturn(Uni.createFrom().item(mockRun));

        client.resumeRun(runId, data, humanTaskId).await().indefinitely();

        verify(runManager).resumeRun(eq(WorkflowRunId.of(runId)), eq(TenantId.of(tenantId)), eq(data), eq(humanTaskId));
    }

    @Test
    void testCloseState() {
        client.close();
        assertThrows(IllegalStateException.class, () -> client.getRun("any"));
    }
}
