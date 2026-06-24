package tech.kayys.gamelan.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.smallrye.mutiny.helpers.test.UniAssertSubscriber;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tech.kayys.gamelan.engine.execution.ExecutionPlan;
import tech.kayys.gamelan.engine.error.ErrorInfo;
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
    void planNextExecution_whenRunIsNotRunning_returnsInertPlan() {
        NodeDefinition node1 = createNode("node1", List.of());
        WorkflowDefinition definition = createWorkflow("wf1", List.of(node1));
        WorkflowRun run = WorkflowRun.create(TENANT, definition, Map.of());

        ExecutionPlan plan = plan(run, definition);

        assertNotNull(plan);
        assertTrue(plan.readyNodes().isEmpty());
        assertFalse(plan.isComplete());
        assertFalse(plan.isStuck());
        assertTrue(plan.outputs().isEmpty());
    }

    @Test
    void planNextExecution_whenInputsAreNull_failsFast() {
        NodeDefinition node1 = createNode("node1", List.of());
        WorkflowDefinition definition = createWorkflow("wf1", List.of(node1));
        WorkflowRun run = startedRun(definition);

        assertThrows(IllegalArgumentException.class,
                () -> engine.planNextExecution(null, definition).await().indefinitely());
        assertThrows(IllegalArgumentException.class,
                () -> engine.planNextExecution(run, null).await().indefinitely());
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
    void planNextExecution_whenOnlyNonCriticalWorkFailed_returnsCompletePlan() {
        NodeDefinition node1 = createNode("node1", List.of());
        WorkflowDefinition definition = createWorkflow("wf1", List.of(node1));
        WorkflowRun run = startedRun(definition);

        run.startNode(node1.id(), 1);
        run.failNode(node1.id(), 1, error());

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
        NodeDefinition node1 = createNode("node1", List.of(), RetryPolicy.none(), true);
        WorkflowDefinition definition = createWorkflow("wf1", List.of(node1));
        WorkflowRun run = startedRun(definition);
        run.startNode(node1.id(), 1);
        run.failNode(node1.id(), 1, error());
        forceStatus(run, RunStatus.RUNNING);

        ExecutionPlan plan = plan(run, definition);

        assertNotNull(plan);
        assertTrue(plan.readyNodes().isEmpty());
        assertFalse(plan.isComplete());
        assertTrue(plan.isStuck());
    }

    @Test
    void planNextExecution_whenDefinitionIsInvalid_failsBeforePlanningAndRecordsFailureMetric() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        engine.meterRegistry = meterRegistry;
        NodeDefinition node1 = createNode("node1", List.of(NodeId.of("nonexistent")));
        WorkflowDefinition definition = createWorkflow("wf-invalid", List.of(node1));
        WorkflowRun run = startedRunForDefinitionId(definition.id().value());

        WorkflowPlanningException error = assertThrows(WorkflowPlanningException.class,
                () -> engine.planNextExecution(run, definition).await().indefinitely());

        assertTrue(error.getMessage().contains("Workflow definition is invalid and cannot be planned"));
        assertTrue(error.validationErrors().toString().contains("references unknown dependency"));
        assertEquals(1.0, counter(meterRegistry, "gamelan.workflow.planning.plans", "outcome", "failure"));
        assertEquals(1, timerCount(meterRegistry, "gamelan.workflow.planning.duration", "outcome", "failure"));
    }

    @Test
    void planNextExecution_whenPlanningValidationDisabled_allowsLegacyInvalidDefinitionToReportStuck() throws Exception {
        engine.planningValidationEnabled = false;
        NodeDefinition node1 = createNode("node1", List.of(NodeId.of("nonexistent")));
        WorkflowDefinition definition = createWorkflow("wf-legacy-invalid", List.of(node1));
        WorkflowRun run = runningRunWithoutNodeExecutionsForDefinitionId(definition.id().value());

        ExecutionPlan plan = plan(run, definition);

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

    @Test
    void planNextExecution_whenRetryDelayNotDue_waitsWithoutMarkingWorkflowStuck() {
        NodeDefinition node1 = createNode(
                "node1",
                List.of(),
                new RetryPolicy(2, Duration.ofMinutes(5), Duration.ofMinutes(5), 1.0, List.of()));
        WorkflowDefinition definition = createWorkflow("wf1", List.of(node1));
        WorkflowRun run = startedRun(definition);

        run.startNode(node1.id(), 1);
        run.failNode(node1.id(), 1, error());

        engine.clock = Clock.fixed(run.getNodeExecution(node1.id()).getRetryAt().minusMillis(1), ZoneOffset.UTC);
        ExecutionPlan waiting = plan(run, definition);

        assertTrue(waiting.readyNodes().isEmpty());
        assertFalse(waiting.isComplete());
        assertFalse(waiting.isStuck());

        engine.clock = Clock.fixed(run.getNodeExecution(node1.id()).getRetryAt(), ZoneOffset.UTC);
        ExecutionPlan due = plan(run, definition);

        assertEquals(List.of(node1.id()), due.readyNodes());
        assertFalse(due.isStuck());
    }

    @Test
    void planNextExecution_recordsBoundedPlanningOutcomeMetrics() throws Exception {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        engine.meterRegistry = meterRegistry;

        NodeDefinition readyNode = createNode("ready", List.of());
        WorkflowDefinition readyDefinition = createWorkflow("wf-ready", List.of(readyNode));
        plan(startedRun(readyDefinition), readyDefinition);

        NodeDefinition completeNode = createNode("complete", List.of());
        WorkflowDefinition completeDefinition = createWorkflow("wf-complete", List.of(completeNode));
        WorkflowRun completeRun = startedRun(completeDefinition);
        completeRun.startNode(completeNode.id(), 1);
        completeRun.completeNode(completeNode.id(), 1, Map.of());
        forceStatus(completeRun, RunStatus.RUNNING);
        plan(completeRun, completeDefinition);

        NodeDefinition inactiveNode = createNode("inactive", List.of());
        WorkflowDefinition inactiveDefinition = createWorkflow("wf-inactive", List.of(inactiveNode));
        plan(WorkflowRun.create(TENANT, inactiveDefinition, Map.of()), inactiveDefinition);

        NodeDefinition stuckNode = createNode("stuck", List.of(), RetryPolicy.none(), true);
        WorkflowDefinition stuckDefinition = createWorkflow("wf-stuck", List.of(stuckNode));
        WorkflowRun stuckRun = startedRun(stuckDefinition);
        stuckRun.startNode(stuckNode.id(), 1);
        stuckRun.failNode(stuckNode.id(), 1, error());
        forceStatus(stuckRun, RunStatus.RUNNING);
        plan(stuckRun, stuckDefinition);

        NodeDefinition waitingNode = createNode(
                "waiting",
                List.of(),
                new RetryPolicy(2, Duration.ofMinutes(5), Duration.ofMinutes(5), 1.0, List.of()));
        WorkflowDefinition waitingDefinition = createWorkflow("wf-waiting", List.of(waitingNode));
        WorkflowRun waitingRun = startedRun(waitingDefinition);
        waitingRun.startNode(waitingNode.id(), 1);
        waitingRun.failNode(waitingNode.id(), 1, error());
        engine.clock = Clock.fixed(waitingRun.getNodeExecution(waitingNode.id()).getRetryAt().minusMillis(1), ZoneOffset.UTC);
        plan(waitingRun, waitingDefinition);

        assertPlanningOutcome(meterRegistry, "ready", 1.0, 1, 1, 1.0);
        assertPlanningOutcome(meterRegistry, "complete", 1.0, 1, 1, 0.0);
        assertPlanningOutcome(meterRegistry, "inactive", 1.0, 1, 1, 0.0);
        assertPlanningOutcome(meterRegistry, "stuck", 1.0, 1, 1, 0.0);
        assertPlanningOutcome(meterRegistry, "waiting", 1.0, 1, 1, 0.0);
    }

    @Test
    void planNextExecution_recordsFailurePlanningMetricWhenPlanningThrows() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        engine.meterRegistry = meterRegistry;
        engine.definitionCompiler = new WorkflowDefinitionCompiler() {
            @Override
            public CompiledWorkflowDefinition compile(WorkflowDefinition definition) {
                throw new IllegalStateException("compile failed");
            }
        };
        NodeDefinition node = createNode("node", List.of());
        WorkflowDefinition definition = createWorkflow("wf-failure", List.of(node));
        WorkflowRun run = startedRun(definition);

        assertThrows(IllegalStateException.class,
                () -> engine.planNextExecution(run, definition).await().indefinitely());

        assertEquals(1.0, counter(meterRegistry, "gamelan.workflow.planning.plans", "outcome", "failure"));
        assertEquals(1, timerCount(meterRegistry, "gamelan.workflow.planning.duration", "outcome", "failure"));
        assertEquals(0, summaryCount(meterRegistry, "gamelan.workflow.planning.ready_nodes", "outcome", "failure"));
    }

    @Test
    void normalizeReadyNodes_preservesSchedulerOrderWithoutDroppingComputedReadyNodes() {
        NodeId first = NodeId.of("first");
        NodeId second = NodeId.of("second");
        NodeId third = NodeId.of("third");

        List<NodeId> normalized = engine.normalizeReadyNodes(
                List.of(second, NodeId.of("unknown"), second),
                List.of(first, second, third));

        assertEquals(List.of(second, first, third), normalized);
    }

    @Test
    void normalizeReadyNodes_fallsBackToComputedOrderWhenSchedulerReturnsNullOrOnlyInvalidNodes() {
        NodeId first = NodeId.of("first");
        NodeId second = NodeId.of("second");

        assertEquals(
                List.of(first, second),
                engine.normalizeReadyNodes(null, List.of(first, second)));
        assertEquals(
                List.of(first, second),
                engine.normalizeReadyNodes(List.of(NodeId.of("unknown")), List.of(first, second)));
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

    private WorkflowRun startedRunForDefinitionId(String definitionId) {
        WorkflowDefinition validDefinition = createWorkflow(definitionId, List.of(createNode("start", List.of())));
        return startedRun(validDefinition);
    }

    private WorkflowRun runningRunWithoutNodeExecutionsForDefinitionId(String definitionId) throws Exception {
        WorkflowDefinition validDefinition = createWorkflow(definitionId, List.of(createNode("start", List.of())));
        WorkflowRun run = WorkflowRun.create(TENANT, validDefinition, Map.of());
        forceStatus(run, RunStatus.RUNNING);
        return run;
    }

    private NodeDefinition createNode(String id, List<NodeId> dependencies) {
        return createNode(id, dependencies, RetryPolicy.none());
    }

    private NodeDefinition createNode(String id, List<NodeId> dependencies, RetryPolicy retryPolicy) {
        return createNode(id, dependencies, retryPolicy, false);
    }

    private NodeDefinition createNode(
            String id,
            List<NodeId> dependencies,
            RetryPolicy retryPolicy,
            boolean critical) {
        return new NodeDefinition(
                NodeId.of(id),
                id,
                NodeType.TASK,
                "local",
                Map.of(),
                dependencies,
                List.of(),
                retryPolicy,
                Duration.ZERO,
                critical);
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

    private static ErrorInfo error() {
        return new ErrorInfo("TEST_ERROR", "boom", "", Map.of());
    }

    private static void assertPlanningOutcome(
            SimpleMeterRegistry meterRegistry,
            String outcome,
            double expectedCounter,
            long expectedTimerCount,
            long expectedReadyNodeSamples,
            double expectedReadyNodeTotal) {
        assertEquals(expectedCounter, counter(meterRegistry, "gamelan.workflow.planning.plans", "outcome", outcome));
        assertEquals(expectedTimerCount, timerCount(meterRegistry, "gamelan.workflow.planning.duration", "outcome", outcome));
        assertEquals(
                expectedReadyNodeSamples,
                summaryCount(meterRegistry, "gamelan.workflow.planning.ready_nodes", "outcome", outcome));
        assertEquals(
                expectedReadyNodeTotal,
                summaryTotal(meterRegistry, "gamelan.workflow.planning.ready_nodes", "outcome", outcome));
    }

    private static double counter(SimpleMeterRegistry meterRegistry, String name, String... tags) {
        var counter = meterRegistry.find(name).tags(tags).counter();
        return counter != null ? counter.count() : 0.0;
    }

    private static long timerCount(SimpleMeterRegistry meterRegistry, String name, String... tags) {
        var timer = meterRegistry.find(name).tags(tags).timer();
        return timer != null ? timer.count() : 0;
    }

    private static long summaryCount(SimpleMeterRegistry meterRegistry, String name, String... tags) {
        var summary = meterRegistry.find(name).tags(tags).summary();
        return summary != null ? summary.count() : 0;
    }

    private static double summaryTotal(SimpleMeterRegistry meterRegistry, String name, String... tags) {
        var summary = meterRegistry.find(name).tags(tags).summary();
        return summary != null ? summary.totalAmount() : 0.0;
    }
}
