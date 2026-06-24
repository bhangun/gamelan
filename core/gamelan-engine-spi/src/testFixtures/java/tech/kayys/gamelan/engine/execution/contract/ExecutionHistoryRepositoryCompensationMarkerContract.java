package tech.kayys.gamelan.engine.execution.contract;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import tech.kayys.gamelan.engine.execution.ExecutionHistoryRepository;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;

/**
 * Shared conformance tests for durable compensation-node idempotency markers.
 */
public interface ExecutionHistoryRepositoryCompensationMarkerContract {

    ExecutionHistoryRepository newExecutionHistoryRepository();

    @Test
    default void compensationMarkers_areTenantScopedAndIdempotent() {
        ExecutionHistoryRepository repository = newExecutionHistoryRepository();
        WorkflowRunId runId = WorkflowRunId.of("contract-compensation-marker-run");
        TenantId tenantA = TenantId.of("tenant-a");
        TenantId tenantB = TenantId.of("tenant-b");
        NodeId nodeId = NodeId.of("node-1");

        assertFalse(repository.isCompensationNodeProcessed(runId, tenantA, nodeId).await().indefinitely());
        assertTrue(repository.markCompensationNodeProcessed(runId, tenantA, nodeId).await().indefinitely());
        assertTrue(repository.isCompensationNodeProcessed(runId, tenantA, nodeId).await().indefinitely());
        assertFalse(repository.isCompensationNodeProcessed(runId, tenantB, nodeId).await().indefinitely());
        assertTrue(repository.markCompensationNodeProcessed(runId, tenantB, nodeId).await().indefinitely());
        assertFalse(repository.markCompensationNodeProcessed(runId, tenantA, nodeId).await().indefinitely());
    }

    @Test
    default void compensationMarkers_honorLegacyGlobalMarkers() {
        ExecutionHistoryRepository repository = newExecutionHistoryRepository();
        WorkflowRunId runId = WorkflowRunId.of("contract-legacy-compensation-marker-run");
        TenantId tenant = TenantId.of("tenant-a");
        NodeId nodeId = NodeId.of("legacy-node");

        assertTrue(repository.markCompensationNodeProcessed(runId, nodeId).await().indefinitely());
        assertTrue(repository.isCompensationNodeProcessed(runId, tenant, nodeId).await().indefinitely());
        assertFalse(repository.markCompensationNodeProcessed(runId, tenant, nodeId).await().indefinitely());
        assertFalse(repository.markCompensationNodeProcessed(runId, nodeId).await().indefinitely());
    }
}
