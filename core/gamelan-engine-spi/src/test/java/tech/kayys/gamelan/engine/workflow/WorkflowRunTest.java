package tech.kayys.gamelan.engine.workflow;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tech.kayys.gamelan.engine.error.ErrorInfo;
import tech.kayys.gamelan.engine.event.WorkflowStartedEvent;
import tech.kayys.gamelan.engine.node.InputDefinition;
import tech.kayys.gamelan.engine.node.NodeDefinition;
import tech.kayys.gamelan.engine.node.NodeExecutionStatus;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.node.NodeType;
import tech.kayys.gamelan.engine.run.RetryPolicy;
import tech.kayys.gamelan.engine.run.RunStatus;
import tech.kayys.gamelan.engine.saga.CompensationPolicy;
import tech.kayys.gamelan.engine.tenant.TenantId;

class WorkflowRunTest {

    private static final TenantId TENANT = TenantId.of("tenant-1");

    @Test
    void create_appliesInputDefaultsToContextWithoutMutatingCallerInputs() {
        Map<String, Object> callerInputs = new HashMap<>();
        WorkflowDefinition definition = workflow(
                "defaults",
                List.of(node("start")),
                Map.of("topic", new InputDefinition("topic", "string", true, "orders", null)),
                CompensationPolicy.disabled());

        WorkflowRun run = WorkflowRun.create(TENANT, definition, callerInputs);

        assertEquals("orders", run.getContext().getVariable("topic"));
        assertFalse(callerInputs.containsKey("topic"));

        WorkflowStartedEvent started = (WorkflowStartedEvent) run.getUncommittedEvents().stream()
                .filter(WorkflowStartedEvent.class::isInstance)
                .findFirst()
                .orElseThrow();
        assertEquals("orders", started.inputs().get("topic"));
    }

    @Test
    void failNode_retriesUntilConfiguredMaxAttempts() {
        NodeDefinition node = node("critical", new RetryPolicy(
                3,
                Duration.ZERO,
                Duration.ZERO,
                1.0,
                List.of()),
                true);
        WorkflowRun run = WorkflowRun.create(TENANT, workflow("retry", List.of(node), Map.of(), CompensationPolicy.disabled()), Map.of());
        NodeId nodeId = node.id();

        run.start();
        run.startNode(nodeId, 1);
        run.failNode(nodeId, 1, error());

        assertEquals(NodeExecutionStatus.RETRYING, run.getNodeExecution(nodeId).getStatus());
        assertEquals(2, run.getNodeExecution(nodeId).getAttempt());
        assertEquals(List.of(nodeId), run.getPendingNodes());

        run.startNode(nodeId, 2);
        run.failNode(nodeId, 2, error());

        assertEquals(NodeExecutionStatus.RETRYING, run.getNodeExecution(nodeId).getStatus());
        assertEquals(3, run.getNodeExecution(nodeId).getAttempt());
        assertEquals(List.of(nodeId), run.getPendingNodes());

        run.startNode(nodeId, 3);
        run.failNode(nodeId, 3, error());

        assertEquals(NodeExecutionStatus.FAILED, run.getNodeExecution(nodeId).getStatus());
        assertEquals(RunStatus.FAILED, run.getStatus());
        assertFalse(run.isCompensating());
    }

    @Test
    void fail_doesNotCompensateWhenPolicyIsDisabled() {
        WorkflowRun run = WorkflowRun.create(
                TENANT,
                workflow("disabled-compensation", List.of(node("start")), Map.of(), CompensationPolicy.disabled()),
                Map.of());

        run.start();
        run.fail(error());

        assertEquals(RunStatus.FAILED, run.getStatus());
        assertFalse(run.isCompensating());
    }

    @Test
    void completeNode_acceptsNullOutputAsEmptyOutput() {
        NodeDefinition node = node("start");
        WorkflowRun run = WorkflowRun.create(
                TENANT,
                workflow("null-output", List.of(node), Map.of(), CompensationPolicy.disabled()),
                Map.of());

        run.start();
        run.startNode(node.id(), 1);

        assertDoesNotThrow(() -> run.completeNode(node.id(), 1, null));
        assertEquals(NodeExecutionStatus.COMPLETED, run.getNodeExecution(node.id()).getStatus());
        assertTrue(run.getNodeExecution(node.id()).getOutput().isEmpty());
        assertEquals(RunStatus.COMPLETED, run.getStatus());
    }

    private static NodeDefinition node(String id) {
        return node(id, RetryPolicy.none(), false);
    }

    private static NodeDefinition node(String id, RetryPolicy retryPolicy, boolean critical) {
        return new NodeDefinition(
                NodeId.of(id),
                id,
                NodeType.TASK,
                "local",
                Map.of(),
                List.of(),
                List.of(),
                retryPolicy,
                Duration.ZERO,
                critical);
    }

    private static WorkflowDefinition workflow(
            String id,
            List<NodeDefinition> nodes,
            Map<String, InputDefinition> inputs,
            CompensationPolicy compensationPolicy) {
        return new WorkflowDefinition(
                WorkflowDefinitionId.of("wf-" + id),
                TENANT,
                id,
                "1.0.0",
                null,
                WorkflowMode.FLOW,
                nodes,
                inputs,
                Map.of(),
                null,
                RetryPolicy.none(),
                compensationPolicy);
    }

    private static ErrorInfo error() {
        return new ErrorInfo("TEST_ERROR", "boom", "", Map.of());
    }
}
