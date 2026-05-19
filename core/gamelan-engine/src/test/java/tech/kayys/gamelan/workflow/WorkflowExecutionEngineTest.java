package tech.kayys.gamelan.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import io.smallrye.mutiny.helpers.test.UniAssertSubscriber;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tech.kayys.gamelan.engine.execution.ExecutionPlan;
import tech.kayys.gamelan.engine.node.NodeDefinition;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.node.NodeType;
import tech.kayys.gamelan.engine.run.RetryPolicy;
import tech.kayys.gamelan.engine.run.RunStatus;
import tech.kayys.gamelan.engine.saga.CompensationPolicy;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinition;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinitionId;
import tech.kayys.gamelan.engine.workflow.WorkflowMode;
import tech.kayys.gamelan.engine.workflow.WorkflowRun;

public class WorkflowExecutionEngineTest {

    private static final TenantId TENANT = TenantId.of("tenant1");

    private WorkflowExecutionEngine engine;

    @BeforeEach
    void setUp() {
        engine = new WorkflowExecutionEngine();
    }

    @Test
    void planNextExecution_whenValidWorkflow_returnsExecutionPlan() {
        NodeDefinition node1 = createNode("node1", List.of());
        NodeDefinition node2 = createNode("node2", List.of(NodeId.of("node1")));
        WorkflowDefinition definition = createWorkflow("wf1", List.of(node1, node2));
        WorkflowRun run = startedRun(definition);

        ExecutionPlan plan = plan(run, definition);

        assertNotNull(plan);
        assertEquals(1, plan.readyNodes().size());
        assertEquals("node1", plan.readyNodes().get(0).value());
        assertFalse(plan.isComplete());
        assertFalse(plan.isStuck());
    }

    @Test
    void planNextExecution_whenAllNodesExecuted_returnsCompletePlan() {
        NodeDefinition node1 = createNode("node1", List.of());
        WorkflowDefinition definition = createWorkflow("wf1", List.of(node1));
        WorkflowRun run = startedRun(definition);

        run.startNode(node1.id(), 1);
        run.completeNode(node1.id(), 1, Map.of());

        ExecutionPlan plan = plan(run, definition);

        assertNotNull(plan);
        assertTrue(plan.readyNodes().isEmpty());
        assertTrue(plan.isComplete());
        assertFalse(plan.isStuck());
    }

    @Test
    void planNextExecution_whenDependenciesNotMet_returnsOnlyRootReadyNode() {
        NodeDefinition node1 = createNode("node1", List.of());
        NodeDefinition node2 = createNode("node2", List.of(NodeId.of("node1")));
        WorkflowDefinition definition = createWorkflow("wf1", List.of(node1, node2));
        WorkflowRun run = startedRun(definition);

        ExecutionPlan plan = plan(run, definition);

        assertNotNull(plan);
        assertEquals(1, plan.readyNodes().size());
        assertEquals("node1", plan.readyNodes().get(0).value());
        assertFalse(plan.isComplete());
        assertFalse(plan.isStuck());
    }

    @Test
    void planNextExecution_whenWorkflowIsStuck_returnsStuckPlan() throws Exception {
        NodeDefinition node1 = createNode("node1", List.of(NodeId.of("nonexistent")));
        WorkflowDefinition definition = createWorkflow("wf1", List.of(node1));
        WorkflowRun run = WorkflowRun.create(TENANT, definition, Map.of());
        forceStatus(run, RunStatus.RUNNING);

        ExecutionPlan plan = plan(run, definition);

        assertNotNull(plan);
        assertTrue(plan.readyNodes().isEmpty());
        assertFalse(plan.isComplete());
        assertTrue(plan.isStuck());
    }

    @Test
    void planNextExecution_whenNodeIsRunning_doesNotMarkWorkflowStuck() {
        NodeDefinition node1 = createNode("node1", List.of());
        WorkflowDefinition definition = createWorkflow("wf1", List.of(node1));
        WorkflowRun run = startedRun(definition);

        run.startNode(node1.id(), 1);

        ExecutionPlan plan = plan(run, definition);

        assertNotNull(plan);
        assertTrue(plan.readyNodes().isEmpty());
        assertFalse(plan.isComplete());
        assertFalse(plan.isStuck());
    }

    private ExecutionPlan plan(WorkflowRun run, WorkflowDefinition definition) {
        return engine.planNextExecution(run, definition)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();
    }

    private WorkflowRun startedRun(WorkflowDefinition definition) {
        WorkflowRun run = WorkflowRun.create(TENANT, definition, Map.of());
        run.start();
        return run;
    }

    private NodeDefinition createNode(String id, List<NodeId> dependencies) {
        return new NodeDefinition(
                NodeId.of(id),
                id,
                NodeType.TASK,
                "local",
                Map.of(),
                dependencies,
                List.of(),
                RetryPolicy.none(),
                Duration.ZERO,
                false);
    }

    private WorkflowDefinition createWorkflow(String id, List<NodeDefinition> nodes) {
        return new WorkflowDefinition(
                WorkflowDefinitionId.of(id),
                TENANT,
                id,
                "1.0.0",
                null,
                WorkflowMode.FLOW,
                nodes,
                Map.of(),
                Map.of(),
                null,
                RetryPolicy.none(),
                CompensationPolicy.disabled());
    }

    private void forceStatus(WorkflowRun run, RunStatus status) throws Exception {
        Field statusField = WorkflowRun.class.getDeclaredField("status");
        statusField.setAccessible(true);
        statusField.set(run, status);
    }
}
