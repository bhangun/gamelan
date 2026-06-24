package tech.kayys.gamelan.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tech.kayys.gamelan.domain.WorkflowRunEntity;
import tech.kayys.gamelan.engine.error.ErrorInfo;
import tech.kayys.gamelan.engine.node.NodeDefinition;
import tech.kayys.gamelan.engine.node.NodeExecutionStatus;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.node.NodeType;
import tech.kayys.gamelan.engine.run.RetryPolicy;
import tech.kayys.gamelan.engine.run.RunStatus;
import tech.kayys.gamelan.engine.saga.CompensationPolicy;
import tech.kayys.gamelan.engine.signal.Signal;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinition;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinitionId;
import tech.kayys.gamelan.engine.workflow.WorkflowMode;
import tech.kayys.gamelan.engine.workflow.WorkflowRun;

class WorkflowRunEntityMapperTest {

    private static final TenantId TENANT = TenantId.of("tenant-1");
    private static final NodeId NODE_ID = NodeId.of("node-1");

    @Test
    void roundTrip_preservesCompletedRunSnapshot() {
        WorkflowDefinition definition = workflow();
        WorkflowRun run = WorkflowRun.create(TENANT, definition, Map.of("input", "value"));

        run.start();
        run.startNode(NODE_ID, 1);
        run.completeNode(NODE_ID, 1, Map.of("result", "ok"));
        run.markEventsAsCommitted();

        WorkflowRunEntity entity = WorkflowRunEntityMapper.toEntity(run);
        WorkflowRun restored = WorkflowRunEntityMapper.toDomain(entity, definition);

        assertEquals(run.getId(), restored.getId());
        assertEquals(TENANT, restored.getTenantId());
        assertEquals("1.0.0", entity.getDefinitionVersion());
        assertEquals("1.0.0", restored.createSnapshot().definitionVersion());
        assertEquals(RunStatus.COMPLETED, restored.getStatus());
        assertEquals("value", restored.getContext().getVariable("input"));
        assertEquals(NodeExecutionStatus.COMPLETED, restored.getNodeExecution(NODE_ID).getStatus());
        assertEquals("ok", restored.getNodeExecution(NODE_ID).getOutput().get("result"));
        assertEquals(run.getVersion(), restored.getVersion());
        assertNotNull(entity.getStartedAt());
        assertNotNull(entity.getCompletedAt());
        assertTrue(restored.getUncommittedEvents().isEmpty());
    }

    @Test
    void roundTrip_preservesFailedNodeErrorSnapshot() {
        WorkflowDefinition definition = workflow();
        WorkflowRun run = WorkflowRun.create(TENANT, definition, Map.of());

        run.start();
        run.startNode(NODE_ID, 1);
        run.failNode(NODE_ID, 1, new ErrorInfo("BOOM", "node failed", "stack", Map.of()));

        WorkflowRun restored = WorkflowRunEntityMapper.toDomain(WorkflowRunEntityMapper.toEntity(run), definition);

        assertEquals(NodeExecutionStatus.FAILED, restored.getNodeExecution(NODE_ID).getStatus());
        assertEquals("BOOM", restored.getNodeExecution(NODE_ID).getLastError().code());
        assertEquals("node failed", restored.getNodeExecution(NODE_ID).getLastError().message());
    }

    @Test
    void roundTrip_preservesRetryDueTime() {
        WorkflowDefinition definition = workflow(List.of(retryingNode()), CompensationPolicy.disabled());
        WorkflowRun run = WorkflowRun.create(TENANT, definition, Map.of());

        run.start();
        run.startNode(NODE_ID, 1);
        run.failNode(NODE_ID, 1, new ErrorInfo("TRANSIENT", "try again", "stack", Map.of()));

        WorkflowRun restored = WorkflowRunEntityMapper.toDomain(WorkflowRunEntityMapper.toEntity(run), definition);

        assertEquals(NodeExecutionStatus.RETRYING, restored.getNodeExecution(NODE_ID).getStatus());
        assertEquals(run.getNodeExecution(NODE_ID).getRetryAt(), restored.getNodeExecution(NODE_ID).getRetryAt());
    }

    @Test
    void roundTrip_preservesSuspendedWaitState() {
        WorkflowDefinition definition = workflow();
        WorkflowRun run = WorkflowRun.create(TENANT, definition, Map.of());

        run.start();
        run.startNode(NODE_ID, 1);
        run.suspend("waiting for callback", NODE_ID);

        WorkflowRun restored = WorkflowRunEntityMapper.toDomain(WorkflowRunEntityMapper.toEntity(run), definition);
        restored.signal(new Signal("approved", NODE_ID, Map.of("approved", true), Instant.now()));

        assertEquals(RunStatus.RUNNING, restored.getStatus());
        assertEquals(true, restored.getContext().getVariable("approved"));
    }

    @Test
    void roundTrip_preservesPendingSignals() {
        WorkflowDefinition definition = workflow();
        WorkflowRun run = WorkflowRun.create(TENANT, definition, Map.of());

        run.start();
        run.startNode(NODE_ID, 1);
        run.signal(new Signal("approved", NODE_ID, Map.of("approved", true), Instant.now()));

        WorkflowRun restored = WorkflowRunEntityMapper.toDomain(WorkflowRunEntityMapper.toEntity(run), definition);
        restored.suspend("waiting for callback", NODE_ID);

        assertEquals(RunStatus.RUNNING, restored.getStatus());
        assertEquals(true, restored.getContext().getVariable("approved"));
    }

    @Test
    void roundTrip_preservesCompensationState() {
        NodeDefinition secondNode = node(NodeId.of("node-2"), List.of(NODE_ID), true);
        WorkflowDefinition definition = workflow(List.of(node(), secondNode), CompensationPolicy.enabledDefault());
        WorkflowRun run = WorkflowRun.create(TENANT, definition, Map.of());

        run.start();
        run.startNode(NODE_ID, 1);
        run.completeNode(NODE_ID, 1, Map.of());
        run.fail(new ErrorInfo("BOOM", "run failed", "stack", Map.of()));

        WorkflowRun restored = WorkflowRunEntityMapper.toDomain(WorkflowRunEntityMapper.toEntity(run), definition);

        assertEquals(RunStatus.COMPENSATING, restored.getStatus());
        assertEquals(NODE_ID, restored.getNextNodeToCompensate());
    }

    private static WorkflowDefinition workflow() {
        return workflow(List.of(node()), CompensationPolicy.disabled());
    }

    private static WorkflowDefinition workflow(List<NodeDefinition> nodes, CompensationPolicy compensationPolicy) {
        return new WorkflowDefinition(
                WorkflowDefinitionId.of("wf-1"),
                TENANT,
                "test-workflow",
                "1.0.0",
                null,
                WorkflowMode.FLOW,
                nodes,
                Map.of(),
                Map.of(),
                null,
                RetryPolicy.none(),
                compensationPolicy);
    }

    private static NodeDefinition node() {
        return node(NODE_ID, List.of(), false);
    }

    private static NodeDefinition node(NodeId nodeId, List<NodeId> dependsOn, boolean critical) {
        return node(nodeId, dependsOn, critical, RetryPolicy.none());
    }

    private static NodeDefinition retryingNode() {
        return node(
                NODE_ID,
                List.of(),
                false,
                new RetryPolicy(2, Duration.ofSeconds(30), Duration.ofSeconds(30), 1.0, List.of()));
    }

    private static NodeDefinition node(
            NodeId nodeId,
            List<NodeId> dependsOn,
            boolean critical,
            RetryPolicy retryPolicy) {
        return new NodeDefinition(
                nodeId,
                nodeId.value(),
                NodeType.TASK,
                "local",
                Map.of(),
                dependsOn,
                List.of(),
                retryPolicy,
                Duration.ZERO,
                critical);
    }
}
