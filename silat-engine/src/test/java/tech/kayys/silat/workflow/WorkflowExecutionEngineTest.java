package tech.kayys.silat.workflow;

import java.util.List;
import java.util.Map;

import io.smallrye.mutiny.helpers.test.UniAssertSubscriber;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import tech.kayys.silat.model.*;
import tech.kayys.silat.execution.*;
import tech.kayys.silat.repository.WorkflowDefinitionRepository;
import tech.kayys.silat.repository.WorkflowRunRepository;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class WorkflowExecutionEngineTest {

    @InjectMocks
    WorkflowExecutionEngine engine;

    @Mock
    WorkflowDefinitionRepository workflowDefinitionRepository;

    @Mock
    WorkflowRunRepository workflowRunRepository;

    @Mock
    EventStore eventStore;

    @Mock
    WorkflowDefinitionRegistry mockRegistry;

    @BeforeEach
    void setUp() {
        // Mocks initialized by MockitoExtension
    }

    @Test
    void planNextExecution_whenValidWorkflow_returnsExecutionPlan() {
        // Arrange
        NodeDefinition node1 = createNode("node1", List.of());
        NodeDefinition node2 = createNode("node2", List.of(new NodeId("node1")));

        WorkflowDefinition definition = createWorkflow("wf1", List.of(node1, node2));

        WorkflowRun run = mock(WorkflowRun.class);
        when(run.getId()).thenReturn(new WorkflowRunId("run1"));
        when(run.getDefinitionId()).thenReturn(new WorkflowDefinitionId("wf1"));
        when(run.getTenantId()).thenReturn(new TenantId("tenant1"));
        when(run.getStatus()).thenReturn(RunStatus.RUNNING);

        // run.getAllNodeExecutions() returns Map<NodeId, NodeExecution>
        when(run.getAllNodeExecutions()).thenReturn(new java.util.HashMap<>());

        ExecutionContext context = mock(ExecutionContext.class);
        when(run.getContext()).thenReturn(context);

        when(run.getNodeExecution(any(NodeId.class))).thenReturn(null);

        // Act
        ExecutionPlan plan = engine.planNextExecution(run, definition)
                .subscribe().withSubscriber(UniAssertSubscriber.create()).awaitItem().getItem();

        // Assert
        assertNotNull(plan);
        assertEquals(1, plan.readyNodes().size());
        assertEquals("node1", plan.readyNodes().get(0).value());
        assertFalse(plan.isComplete());
        assertFalse(plan.isStuck());
    }

    @Test
    void planNextExecution_whenAllNodesExecuted_returnsCompletePlan() {
        // Arrange
        NodeDefinition node1 = createNode("node1", List.of());
        WorkflowDefinition definition = createWorkflow("wf1", List.of(node1));

        WorkflowRun run = mock(WorkflowRun.class);
        when(run.getId()).thenReturn(new WorkflowRunId("run1"));
        when(run.getDefinitionId()).thenReturn(new WorkflowDefinitionId("wf1"));
        when(run.getStatus()).thenReturn(RunStatus.RUNNING);

        // Mock node1 as completed
        NodeExecution execution = mock(NodeExecution.class);
        when(execution.getStatus()).thenReturn(NodeExecutionStatus.COMPLETED);
        when(execution.isCompleted()).thenReturn(true);

        Map<NodeId, NodeExecution> executions = new java.util.HashMap<>();
        executions.put(new NodeId("node1"), execution);
        when(run.getAllNodeExecutions()).thenReturn(executions);

        ExecutionContext context = mock(ExecutionContext.class);
        when(run.getContext()).thenReturn(context);

        when(run.getNodeExecution(new NodeId("node1"))).thenReturn(execution);

        // Act
        ExecutionPlan plan = engine.planNextExecution(run, definition)
                .subscribe().withSubscriber(UniAssertSubscriber.create()).awaitItem().getItem();

        // Assert
        assertNotNull(plan);
        assertTrue(plan.readyNodes().isEmpty());
        assertTrue(plan.isComplete());
        assertFalse(plan.isStuck());
    }

    @Test
    void planNextExecution_whenDependenciesNotMet_returnsEmptyReadyNodes() {
        // Arrange
        NodeDefinition node1 = createNode("node1", List.of());
        NodeDefinition node2 = createNode("node2", List.of(new NodeId("node1")));

        WorkflowDefinition definition = createWorkflow("wf1", List.of(node1, node2));

        WorkflowRun run = mock(WorkflowRun.class);
        when(run.getId()).thenReturn(new WorkflowRunId("run1"));
        when(run.getDefinitionId()).thenReturn(new WorkflowDefinitionId("wf1"));
        when(run.getStatus()).thenReturn(RunStatus.RUNNING);

        when(run.getAllNodeExecutions()).thenReturn(new java.util.HashMap<>());

        ExecutionContext context = mock(ExecutionContext.class);
        when(run.getContext()).thenReturn(context);

        when(run.getNodeExecution(any(NodeId.class))).thenReturn(null);

        // Act
        ExecutionPlan plan = engine.planNextExecution(run, definition)
                .subscribe().withSubscriber(UniAssertSubscriber.create()).awaitItem().getItem();

        // Assert
        assertNotNull(plan);
        assertEquals(1, plan.readyNodes().size()); // Only node1 should be ready
        assertEquals("node1", plan.readyNodes().get(0).value());
        assertFalse(plan.isComplete());
        assertFalse(plan.isStuck());
    }

    @Test
    void planNextExecution_whenWorkflowIsStuck_returnsStuckPlan() {
        // Arrange
        NodeDefinition node1 = createNode("node1", List.of(new NodeId("nonexistent")));
        WorkflowDefinition definition = createWorkflow("wf1", List.of(node1));

        WorkflowRun run = mock(WorkflowRun.class);
        when(run.getId()).thenReturn(new WorkflowRunId("run1"));
        when(run.getDefinitionId()).thenReturn(new WorkflowDefinitionId("wf1"));
        when(run.getStatus()).thenReturn(RunStatus.RUNNING);

        when(run.getAllNodeExecutions()).thenReturn(new java.util.HashMap<>());

        ExecutionContext context = mock(ExecutionContext.class);
        when(run.getContext()).thenReturn(context);

        when(run.getNodeExecution(any(NodeId.class))).thenReturn(null);

        // Act
        ExecutionPlan plan = engine.planNextExecution(run, definition)
                .subscribe().withSubscriber(UniAssertSubscriber.create()).awaitItem().getItem();

        // Assert
        assertNotNull(plan);
        assertTrue(plan.readyNodes().isEmpty());
        assertFalse(plan.isComplete());
        assertTrue(plan.isStuck());
    }

    // Helpers

    private NodeDefinition createNode(String id, List<NodeId> dependencies) {
        NodeDefinition node = mock(NodeDefinition.class);
        NodeId nodeId = new NodeId(id);

        when(node.id()).thenReturn(nodeId);
        when(node.dependsOn()).thenReturn(dependencies);

        return node;
    }

    private WorkflowDefinition createWorkflow(String id, List<NodeDefinition> nodes) {
        WorkflowDefinition workflow = mock(WorkflowDefinition.class);
        WorkflowDefinitionId wfId = new WorkflowDefinitionId(id);

        when(workflow.id()).thenReturn(wfId);
        when(workflow.nodes()).thenReturn(nodes);
        when(workflow.outputs()).thenReturn(Map.of());

        return workflow;
    }
}
