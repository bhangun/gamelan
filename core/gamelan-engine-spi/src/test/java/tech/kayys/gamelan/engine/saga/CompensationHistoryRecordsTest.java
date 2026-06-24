package tech.kayys.gamelan.engine.saga;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tech.kayys.gamelan.engine.error.ErrorInfo;
import tech.kayys.gamelan.engine.node.NodeDefinition;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.node.NodeType;
import tech.kayys.gamelan.engine.run.RetryPolicy;
import tech.kayys.gamelan.engine.run.RunStatus;
import tech.kayys.gamelan.engine.run.Transition;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinition;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinitionId;
import tech.kayys.gamelan.engine.workflow.WorkflowMode;
import tech.kayys.gamelan.engine.workflow.WorkflowRun;

class CompensationHistoryRecordsTest {

    private static final TenantId TENANT = TenantId.of("tenant-1");
    private static final NodeId NODE_1 = NodeId.of("node-1");
    private static final NodeId NODE_2 = NodeId.of("node-2");

    @Test
    void nodeClaimed_buildsTypedRecordWithLeaseAndStateSnapshot() {
        WorkflowRun run = compensatingRun();
        Instant claimedAt = Instant.parse("2026-05-27T01:00:00Z");

        CompensationHistoryRecord record = CompensationHistoryRecords.nodeClaimed(
                run,
                NODE_1,
                "coordinator-1",
                "coordinator-1:claim-1",
                claimedAt,
                Duration.ofMinutes(5));

        assertEquals(CompensationEventTypes.COMPENSATION_NODE_CLAIMED, record.eventType());
        assertEquals("coordinator-1:claim-1", record.message());
        assertEquals("node-1", record.metadata().get(CompensationHistoryMetadata.NODE_ID));
        assertEquals("coordinator-1", record.metadata().get(CompensationHistoryMetadata.COORDINATOR_ID));
        assertEquals("coordinator-1:claim-1", record.metadata().get(CompensationHistoryMetadata.CLAIM_ID));
        assertEquals("PT5M", record.metadata().get(CompensationHistoryMetadata.CLAIM_LEASE));
        assertEquals(300000L, record.metadata().get(CompensationHistoryMetadata.CLAIM_LEASE_MILLIS));
        assertEquals("2026-05-27T01:05:00Z", record.metadata().get(CompensationHistoryMetadata.EXPIRES_AT));
        assertEquals(RunStatus.COMPENSATING.name(), record.metadata().get(CompensationHistoryMetadata.STATUS));
        assertTrue(record.metadata().containsKey(CompensationHistoryMetadata.NODES_TO_COMPENSATE));
    }

    @Test
    void nodeCompleted_preservesCompactCompletionMetadataShape() {
        WorkflowRun run = compensatingRun();
        run.compensateNode(NODE_1);

        CompensationHistoryRecord record = CompensationHistoryRecords.nodeCompleted(run, NODE_1);

        assertEquals(CompensationEventTypes.COMPENSATION_NODE_COMPLETED, record.eventType());
        assertEquals("node-1", record.message());
        assertEquals(List.of(), record.metadata().get(CompensationHistoryMetadata.NODES_TO_COMPENSATE));
        assertEquals(List.of("node-1"), record.metadata().get(CompensationHistoryMetadata.COMPENSATED_NODES));
        assertFalse(record.metadata().containsKey(CompensationHistoryMetadata.COORDINATOR_ID));
    }

    @Test
    void nodeFailedFromException_setsStableFailureMetadata() {
        WorkflowRun run = compensatingRun();

        CompensationHistoryRecord record = CompensationHistoryRecords.nodeFailed(
                run,
                NODE_1,
                "coordinator-1",
                "coordinator-1:claim-1",
                new IllegalStateException("rollback denied"),
                Instant.parse("2026-05-27T02:00:00Z"));

        assertEquals(CompensationEventTypes.COMPENSATION_NODE_FAILED, record.eventType());
        assertEquals("rollback denied", record.message());
        assertEquals(
                CompensationHistoryMetadata.FAILURE_SOURCE_EXCEPTION,
                record.metadata().get(CompensationHistoryMetadata.FAILURE_SOURCE));
        assertEquals("rollback denied", record.metadata().get(CompensationHistoryMetadata.FAILURE_MESSAGE));
        assertEquals(
                IllegalStateException.class.getName(),
                record.metadata().get(CompensationHistoryMetadata.FAILURE_TYPE));
        assertEquals("2026-05-27T02:00:00Z", record.metadata().get(CompensationHistoryMetadata.FAILED_AT));
    }

    private static WorkflowRun compensatingRun() {
        WorkflowRun run = WorkflowRun.create(TENANT, workflow(), Map.of());
        run.start();
        run.startNode(NODE_1, 1);
        run.completeNode(NODE_1, 1, Map.of());
        run.startNode(NODE_2, 1);
        run.failNode(NODE_2, 1, new ErrorInfo("TEST_ERROR", "boom", "", Map.of()));
        assertEquals(RunStatus.COMPENSATING, run.getStatus());
        return run;
    }

    private static WorkflowDefinition workflow() {
        return new WorkflowDefinition(
                WorkflowDefinitionId.of("workflow-1"),
                TENANT,
                "workflow-1",
                "1.0.0",
                null,
                WorkflowMode.FLOW,
                List.of(
                        node(NODE_1, false),
                        node(NODE_2, true)),
                Map.of(),
                Map.of(),
                null,
                RetryPolicy.none(),
                CompensationPolicy.enabledDefault());
    }

    private static NodeDefinition node(NodeId nodeId, boolean critical) {
        return new NodeDefinition(
                nodeId,
                nodeId.value(),
                NodeType.TASK,
                "local",
                Map.of("compensationHandler", "rollback-handler"),
                List.of(),
                List.<Transition>of(),
                RetryPolicy.none(),
                Duration.ZERO,
                critical);
    }
}
