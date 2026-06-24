package tech.kayys.gamelan.sdk.client;

import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tech.kayys.gamelan.engine.execution.ExecutionContext;
import tech.kayys.gamelan.engine.node.NodeExecution;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.run.CreateRunRequest;
import tech.kayys.gamelan.engine.run.RunResponse;
import tech.kayys.gamelan.engine.run.RunStatus;
import tech.kayys.gamelan.engine.signal.Signal;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinitionId;
import tech.kayys.gamelan.engine.workflow.WorkflowRun;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunManager;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LocalWorkflowRunClientTest {

    private static final TenantId TENANT = TenantId.of("tenant-local");

    @Mock
    private WorkflowRunManager runManager;

    @Mock
    private WorkflowRun run;

    private LocalWorkflowRunClient client;

    @BeforeEach
    void setUp() {
        client = new LocalWorkflowRunClient(runManager, TENANT.value());
        configureRun(run, "run-1", RunStatus.CREATED);
    }

    @Test
    void createRunDelegatesWithoutAutoStartWhenDisabled() {
        CreateRunRequest request = new CreateRunRequest("wf-1", null, Map.of("prompt", "hi"), "corr-1", false);
        when(runManager.createRun(any(), any())).thenReturn(Uni.createFrom().item(run));

        RunResponse response = client.createRun(request).await().indefinitely();

        assertEquals("run-1", response.getRunId());
        assertNull(response.getDurationMs());
        assertEquals(1, response.getNodesExecuted());
        verify(runManager).createRun(eq(request), eq(TENANT));
        verify(runManager, never()).startRun(any(), any());
    }

    @Test
    void createRunAutoStartsWhenRequested() {
        WorkflowRun startedRun = mock(WorkflowRun.class);
        configureRun(startedRun, "run-1", RunStatus.RUNNING);
        CreateRunRequest request = new CreateRunRequest("wf-1", null, Map.of(), "corr-1", true);
        when(runManager.createRun(any(), any())).thenReturn(Uni.createFrom().item(run));
        when(runManager.startRun(any(), any())).thenReturn(Uni.createFrom().item(startedRun));

        RunResponse response = client.createRun(request).await().indefinitely();

        assertEquals("RUNNING", response.getStatus());
        verify(runManager).startRun(eq(WorkflowRunId.of("run-1")), eq(TENANT));
    }

    @Test
    void suspendTreatsBlankWaitingNodeAsNone() {
        when(runManager.suspendRun(any(), any(), any(), any())).thenReturn(Uni.createFrom().item(run));

        client.suspendRun("run-1", "manual pause", "  ").await().indefinitely();

        verify(runManager).suspendRun(eq(WorkflowRunId.of("run-1")), eq(TENANT), eq("manual pause"), eq(null));
    }

    @Test
    void signalPassesClientIdempotencyKeyAndTreatsBlankTargetAsBroadcast() {
        when(runManager.signal(any(), any())).thenReturn(Uni.createFrom().voidItem());

        client.signal("run-1", "approved", "  ", Map.of("approved", true), "signal-1")
                .await().indefinitely();

        ArgumentCaptor<Signal> captor = ArgumentCaptor.forClass(Signal.class);
        verify(runManager).signal(eq(WorkflowRunId.of("run-1")), captor.capture());
        assertEquals("signal-1", captor.getValue().idempotencyKey());
        assertNull(captor.getValue().targetNodeId());
    }

    @Test
    void closeStateRejectsCallsSynchronously() {
        client.close();

        assertThrows(IllegalStateException.class, () -> client.getRun("run-1"));
    }

    @Test
    void missingRunManagerFailsWithExplicitLocalTransportError() {
        LocalWorkflowRunClient missingManagerClient = new LocalWorkflowRunClient(null, TENANT.value());

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> missingManagerClient.getActiveRunsCount());

        assertEquals("WorkflowRunManager not provided for LOCAL transport", error.getMessage());
    }

    private static void configureRun(WorkflowRun run, String runId, RunStatus status) {
        ExecutionContext context = mock(ExecutionContext.class);
        lenient().when(context.getVariables()).thenReturn(Map.of("answer", "ok"));
        lenient().when(run.getId()).thenReturn(WorkflowRunId.of(runId));
        lenient().when(run.getStatus()).thenReturn(status);
        lenient().when(run.getDefinitionId()).thenReturn(WorkflowDefinitionId.of("wf-1"));
        lenient().when(run.getCreatedAt()).thenReturn(Instant.EPOCH);
        lenient().when(run.getStartedAt()).thenReturn(status == RunStatus.RUNNING ? Instant.EPOCH.plusSeconds(1) : null);
        lenient().when(run.getCompletedAt()).thenReturn(null);
        lenient().when(run.getContext()).thenReturn(context);
        lenient().when(run.getAllNodeExecutions()).thenReturn(Map.of(NodeId.of("node-1"), mock(NodeExecution.class)));
    }
}
