package tech.kayys.silat.saga.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import tech.kayys.silat.model.*;
import tech.kayys.silat.saga.CompensationPolicy;
import tech.kayys.silat.saga.CompensationResult;
import tech.kayys.silat.saga.CompensationStrategy;
import tech.kayys.silat.workflow.WorkflowDefinitionRegistry;

/**
 * Tests for CompensationCoordinator (Saga Pattern)
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class CompensationCoordinatorTest {

        @Mock
        private WorkflowDefinitionRegistry definitionRegistry;

        @InjectMocks
        private CompensationCoordinator coordinator;

        private WorkflowRun failedRun;
        private WorkflowDefinition definition;
        private NodeId node1;
        private NodeId node2;

        @BeforeEach
        void setUp() {
                node1 = new NodeId("node-1");
                node2 = new NodeId("node-2");

                // Create a failed workflow run with completed nodes
                failedRun = createFailedWorkflowRun();

                // Create workflow definition with compensation policy
                definition = createWorkflowDefinition();
        }

        @Test
        void compensate_withNoPolicy_returnsSuccess() {
                // Given: Definition without compensation policy
                WorkflowDefinition defWithoutPolicy = mock(WorkflowDefinition.class);
                when(defWithoutPolicy.compensationPolicy()).thenReturn(null);
                when(definitionRegistry.getDefinition(any(), any()))
                                .thenReturn(io.smallrye.mutiny.Uni.createFrom().item(defWithoutPolicy));

                // When
                CompensationResult result = coordinator.compensate(failedRun)
                                .await().atMost(Duration.ofSeconds(5));

                // Then
                assertTrue(result.success());
                assertEquals("No compensation needed", result.message());
        }

        @Test
        void compensate_withNoCompletedNodes_returnsSuccess() {
                // Given: Run with no completed nodes
                WorkflowRun runWithoutNodes = mock(WorkflowRun.class);
                when(runWithoutNodes.getId()).thenReturn(new WorkflowRunId("run-1"));
                when(runWithoutNodes.getDefinitionId()).thenReturn(new WorkflowDefinitionId("def-1"));
                when(runWithoutNodes.getTenantId()).thenReturn(new TenantId("tenant-1"));
                when(runWithoutNodes.getAllNodeExecutions()).thenReturn(Map.of());

                when(definitionRegistry.getDefinition(any(), any()))
                                .thenReturn(io.smallrye.mutiny.Uni.createFrom().item(definition));

                // When
                CompensationResult result = coordinator.compensate(runWithoutNodes)
                                .await().atMost(Duration.ofSeconds(5));

                // Then
                assertTrue(result.success());
                assertEquals("No nodes to compensate", result.message());
        }

        @Test
        void compensate_withSequentialStrategy_compensatesInReverseOrder() {
                // Given: Sequential compensation policy
                CompensationPolicy policy = new CompensationPolicy(
                                true,
                                CompensationStrategy.SEQUENTIAL,
                                Duration.ofMinutes(5),
                                true,
                                3);

                WorkflowDefinition defWithPolicy = mock(WorkflowDefinition.class);
                when(defWithPolicy.compensationPolicy()).thenReturn(policy);
                when(defWithPolicy.findNode(any())).thenReturn(Optional.of(createNodeDefinition()));

                when(definitionRegistry.getDefinition(any(), any()))
                                .thenReturn(io.smallrye.mutiny.Uni.createFrom().item(defWithPolicy));

                // When
                CompensationResult result = coordinator.compensate(failedRun)
                                .await().atMost(Duration.ofSeconds(5));

                // Then
                assertTrue(result.success());
                assertEquals("Sequential compensation completed", result.message());
        }

        @Test
        void compensate_withParallelStrategy_compensatesAllAtOnce() {
                // Given: Parallel compensation policy
                CompensationPolicy policy = new CompensationPolicy(
                                true,
                                CompensationStrategy.PARALLEL,
                                Duration.ofMinutes(5),
                                false,
                                3);

                WorkflowDefinition defWithPolicy = mock(WorkflowDefinition.class);
                when(defWithPolicy.compensationPolicy()).thenReturn(policy);
                when(defWithPolicy.findNode(any())).thenReturn(Optional.of(createNodeDefinition()));

                when(definitionRegistry.getDefinition(any(), any()))
                                .thenReturn(io.smallrye.mutiny.Uni.createFrom().item(defWithPolicy));

                // When
                CompensationResult result = coordinator.compensate(failedRun)
                                .await().atMost(Duration.ofSeconds(5));

                // Then
                assertTrue(result.success());
                assertEquals("Parallel compensation completed", result.message());
        }

        @Test
        void compensate_withCustomStrategy_fallsBackToSequential() {
                // Given: Custom compensation policy
                CompensationPolicy policy = new CompensationPolicy(
                                true,
                                CompensationStrategy.CUSTOM,
                                Duration.ofMinutes(10),
                                true,
                                3);

                WorkflowDefinition defWithPolicy = mock(WorkflowDefinition.class);
                when(defWithPolicy.compensationPolicy()).thenReturn(policy);
                when(defWithPolicy.findNode(any())).thenReturn(Optional.of(createNodeDefinition()));

                when(definitionRegistry.getDefinition(any(), any()))
                                .thenReturn(io.smallrye.mutiny.Uni.createFrom().item(defWithPolicy));

                // When
                CompensationResult result = coordinator.compensate(failedRun)
                                .await().atMost(Duration.ofSeconds(5));

                // Then
                assertTrue(result.success());
                assertEquals("Sequential compensation completed", result.message());
        }

        @Test
        void needsCompensation_withFailedRunAndCompletedNodes_returnsTrue() {
                // When
                boolean needs = coordinator.needsCompensation(failedRun);

                // Then
                assertTrue(needs);
        }

        @Test
        void needsCompensation_withSuccessfulRun_returnsFalse() {
                // Given: Successful run
                WorkflowRun successRun = mock(WorkflowRun.class);
                when(successRun.getStatus()).thenReturn(RunStatus.COMPLETED);

                // When
                boolean needs = coordinator.needsCompensation(successRun);

                // Then
                assertFalse(needs);
        }

        @Test
        void compensateNode_withNodeNotFound_returnsFailure() {
                // Given
                WorkflowDefinition defWithoutNode = mock(WorkflowDefinition.class);
                when(defWithoutNode.findNode(any())).thenReturn(Optional.empty());

                // When
                CompensationResult result = coordinator.compensateNode(
                                failedRun, defWithoutNode, node1).await().atMost(Duration.ofSeconds(5));

                // Then
                assertFalse(result.success());
                assertEquals("Node not found", result.message());
        }

        @Test
        void compensateNode_withNoCompensationHandler_returnsSuccess() {
                // Given: Node without compensation handler
                NodeDefinition nodeDef = mock(NodeDefinition.class);
                when(nodeDef.configuration()).thenReturn(Map.of());

                WorkflowDefinition defWithNode = mock(WorkflowDefinition.class);
                when(defWithNode.findNode(any())).thenReturn(Optional.of(nodeDef));

                // When
                CompensationResult result = coordinator.compensateNode(
                                failedRun, defWithNode, node1).await().atMost(Duration.ofSeconds(5));

                // Then
                assertTrue(result.success());
                assertEquals("No compensation needed", result.message());
        }

        @Test
        void compensateNode_withCompensationHandler_executesCompensation() {
                // Given: Node with compensation handler
                NodeDefinition nodeDef = createNodeDefinition();

                WorkflowDefinition defWithNode = mock(WorkflowDefinition.class);
                when(defWithNode.findNode(any())).thenReturn(Optional.of(nodeDef));

                // When
                CompensationResult result = coordinator.compensateNode(
                                failedRun, defWithNode, node1).await().atMost(Duration.ofSeconds(5));

                // Then
                assertTrue(result.success());
                assertEquals("Node compensated", result.message());
        }

        // Helper methods

        private WorkflowRun createFailedWorkflowRun() {
                WorkflowRun run = mock(WorkflowRun.class);
                when(run.getId()).thenReturn(new WorkflowRunId("run-1"));
                when(run.getDefinitionId()).thenReturn(new WorkflowDefinitionId("def-1"));
                when(run.getTenantId()).thenReturn(new TenantId("tenant-1"));
                when(run.getStatus()).thenReturn(RunStatus.FAILED);

                // Create completed node executions
                NodeExecution exec1 = mock(NodeExecution.class);
                when(exec1.isCompleted()).thenReturn(true);

                NodeExecution exec2 = mock(NodeExecution.class);
                when(exec2.isCompleted()).thenReturn(true);

                Map<NodeId, NodeExecution> executions = new HashMap<>();
                executions.put(node1, exec1);
                executions.put(node2, exec2);

                when(run.getAllNodeExecutions()).thenReturn(executions);

                return run;
        }

        private WorkflowDefinition createWorkflowDefinition() {
                WorkflowDefinition def = mock(WorkflowDefinition.class);

                CompensationPolicy policy = CompensationPolicy.enabledDefault();
                when(def.compensationPolicy()).thenReturn(policy);

                NodeDefinition nodeDef = createNodeDefinition();
                when(def.findNode(any())).thenReturn(Optional.of(nodeDef));

                return def;
        }

        private NodeDefinition createNodeDefinition() {
                NodeDefinition nodeDef = mock(NodeDefinition.class);

                Map<String, Object> config = new HashMap<>();
                config.put("compensationHandler", "rollback-handler");

                when(nodeDef.configuration()).thenReturn(config);

                return nodeDef;
        }
}
