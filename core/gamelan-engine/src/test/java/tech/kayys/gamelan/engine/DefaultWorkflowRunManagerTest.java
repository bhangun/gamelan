package tech.kayys.gamelan.engine;

import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.time.Clock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tech.kayys.gamelan.engine.impl.DefaultWorkflowRunManager;
import tech.kayys.gamelan.engine.node.DefaultNodeExecutionResult;
import tech.kayys.gamelan.engine.node.NodeExecutionStatus;
import tech.kayys.gamelan.engine.workflow.WorkflowRun;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.run.RunStatus;
import tech.kayys.gamelan.engine.run.CreateRunRequest;
import tech.kayys.gamelan.engine.signal.Signal;
import tech.kayys.gamelan.engine.repository.WorkflowRunRepository;
import tech.kayys.gamelan.engine.execution.ExecutionHistoryRepository;
import tech.kayys.gamelan.scheduler.WorkflowScheduler;
import tech.kayys.gamelan.security.TenantSecurityContext;
import tech.kayys.gamelan.core.workflow.WorkflowDefinitionRegistry;

import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import io.smallrye.mutiny.Uni;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class DefaultWorkflowRunManagerTest {

        @InjectMocks
        DefaultWorkflowRunManager runManager;

        @Mock
        WorkflowRunRepository repository;

        @Mock
        ExecutionHistoryRepository historyRepository;

        @Mock
        WorkflowScheduler scheduler;

        @Mock
        WorkflowDefinitionRegistry definitionRegistry;

        @Mock
        TenantSecurityContext tenantContext;

        @Mock
        Clock clock;

        private WorkflowRun mockRun;
        private WorkflowRunId runId;
        private TenantId tenantId;

        @BeforeEach
        void setUp() {
                runId = WorkflowRunId.of(UUID.randomUUID().toString());
                tenantId = TenantId.of("test-tenant");
                mockRun = Mockito.mock(WorkflowRun.class);

                // Common Mocks
                when(tenantContext.validateAccess(any(TenantId.class)))
                                .thenReturn(Uni.createFrom().voidItem());

                // Mock withLock to execute the action immediately with the mockRun
                when(repository.withLock(eq(runId), any()))
                                .thenAnswer(invocation -> {
                                        Function<WorkflowRun, Uni<?>> action = invocation.getArgument(1);
                                        return action.apply(mockRun);
                                });

                when(mockRun.getId()).thenReturn(runId);
                when(mockRun.getStatus()).thenReturn(RunStatus.RUNNING);
        }

        @Test
        void testCreateRun_ThrowsException() {
                CreateRunRequest request = CreateRunRequest.builder()
                                .workflowId("test-def")
                                .workflowVersion("1.0.0")
                                .inputs(Map.of())
                                .correlationId("cor-id")
                                .autoStart(true)
                                .build();

                assertThrows(UnsupportedOperationException.class,
                                () -> runManager.createRun(request, tenantId).await().indefinitely());
        }

        @Test
        void testHandleNodeResult_Success_NotProcessed() {
                NodeId nodeId = NodeId.of("node-1");
                DefaultNodeExecutionResult result = new DefaultNodeExecutionResult(
                                runId,
                                nodeId,
                                1,
                                NodeExecutionStatus.COMPLETED,
                                Map.of("key", "value"),
                                null,
                                null);

                // Mock history behavior
                when(historyRepository.isNodeResultProcessed(eq(runId), eq(nodeId), eq(1)))
                                .thenReturn(Uni.createFrom().item(false));
                when(historyRepository.append(eq(runId), anyString(), anyString(), anyMap()))
                                .thenReturn(Uni.createFrom().voidItem());

                // Mock update behavior
                when(repository.update(any(WorkflowRun.class)))
                                .thenReturn(Uni.createFrom().item(mockRun));

                // Execute
                runManager.handleNodeResult(runId, result).await().indefinitely();

                // Verify interactions
                verify(historyRepository).isNodeResultProcessed(eq(runId), eq(nodeId), eq(1));
                verify(historyRepository).append(eq(runId), eq(ExecutionEventTypes.NODE_COMPLETED), anyString(),
                                anyMap());
                verify(mockRun).completeNode(eq(nodeId), eq(1), anyMap());
                verify(repository).update(mockRun);
        }

        @Test
        void testHandleNodeResult_Success_AlreadyProcessed() {
                NodeId nodeId = NodeId.of("node-1");
                DefaultNodeExecutionResult result = new DefaultNodeExecutionResult(
                                runId,
                                nodeId,
                                1,
                                NodeExecutionStatus.COMPLETED,
                                Map.of("key", "value"),
                                null,
                                null);

                // Mock history behavior - Already Processed
                when(historyRepository.isNodeResultProcessed(eq(runId), eq(nodeId), eq(1)))
                                .thenReturn(Uni.createFrom().item(true));

                // Execute
                runManager.handleNodeResult(runId, result).await().indefinitely();

                // Verify interactions
                verify(historyRepository).isNodeResultProcessed(eq(runId), eq(nodeId), eq(1));
                // Should NOT append or update
                verify(historyRepository, never()).append(any(), any(), any(), any());
                verify(repository, never()).update(any());
                verify(mockRun, never()).completeNode(any(), anyInt(), any());
        }

        @Test
        void testSignal() {
                Signal signal = new Signal("test-signal", NodeId.of("target"), Map.of("foo", "bar"),
                                java.time.Instant.now());

                when(historyRepository.append(eq(runId), eq(ExecutionEventTypes.SIGNAL_RECEIVED), anyString(),
                                anyMap()))
                                .thenReturn(Uni.createFrom().voidItem());

                runManager.signal(runId, signal).await().indefinitely();

                verify(historyRepository).append(eq(runId), eq(ExecutionEventTypes.SIGNAL_RECEIVED), eq("test-signal"),
                                eq(Map.of("foo", "bar")));
        }
}
