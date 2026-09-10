package tech.kayys.gamelan.engine.execution.contract;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import tech.kayys.gamelan.engine.execution.ExecutionHistoryRepository;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;

/**
 * Shared conformance tests for node-result and external-signal idempotency markers.
 */
public interface ExecutionHistoryRepositoryIdempotencyMarkerContract {

    ExecutionHistoryRepository newExecutionHistoryRepository();

    @Test
    default void idempotencyMarkers_nodeResultMarkersAreTenantScopedAndIdempotent() {
        ExecutionHistoryRepository repository = newExecutionHistoryRepository();
        WorkflowRunId runId = WorkflowRunId.of("contract-node-result-marker-run");
        TenantId tenantA = TenantId.of("tenant-a");
        TenantId tenantB = TenantId.of("tenant-b");
        NodeId nodeId = NodeId.of("node-1");

        assertFalse(repository.isNodeResultProcessed(runId, tenantA, nodeId, 1).await().indefinitely());
        assertTrue(repository.markNodeResultProcessed(runId, tenantA, nodeId, 1).await().indefinitely());
        assertTrue(repository.isNodeResultProcessed(runId, tenantA, nodeId, 1).await().indefinitely());
        assertFalse(repository.isNodeResultProcessed(runId, tenantB, nodeId, 1).await().indefinitely());
        assertTrue(repository.markNodeResultProcessed(runId, tenantB, nodeId, 1).await().indefinitely());
        assertFalse(repository.markNodeResultProcessed(runId, tenantA, nodeId, 1).await().indefinitely());

        assertFalse(repository.isNodeResultProcessed(runId, tenantA, nodeId, 2).await().indefinitely());
        assertTrue(repository.markNodeResultProcessed(runId, tenantA, nodeId, 2).await().indefinitely());
        assertTrue(repository.isNodeResultProcessed(runId, tenantA, nodeId, 2).await().indefinitely());
    }

    @Test
    default void idempotencyMarkers_nodeResultMarkersHonorLegacyGlobalMarkers() {
        ExecutionHistoryRepository repository = newExecutionHistoryRepository();
        WorkflowRunId runId = WorkflowRunId.of("contract-legacy-node-result-marker-run");
        TenantId tenant = TenantId.of("tenant-a");
        NodeId nodeId = NodeId.of("legacy-node");

        assertTrue(repository.markNodeResultProcessed(runId, nodeId, 3).await().indefinitely());
        assertTrue(repository.isNodeResultProcessed(runId, tenant, nodeId, 3).await().indefinitely());
        assertFalse(repository.markNodeResultProcessed(runId, tenant, nodeId, 3).await().indefinitely());
        assertFalse(repository.markNodeResultProcessed(runId, nodeId, 3).await().indefinitely());
    }

    @Test
    default void idempotencyMarkers_externalSignalMarkersAreTenantScopedAndIdempotent() {
        ExecutionHistoryRepository repository = newExecutionHistoryRepository();
        WorkflowRunId runId = WorkflowRunId.of("contract-external-signal-marker-run");
        TenantId tenantA = TenantId.of("tenant-a");
        TenantId tenantB = TenantId.of("tenant-b");
        String idempotencyKey = "signal-token-hash";

        assertFalse(repository.isExternalSignalProcessed(runId, tenantA, idempotencyKey).await().indefinitely());
        assertTrue(repository.markExternalSignalProcessed(runId, tenantA, idempotencyKey).await().indefinitely());
        assertTrue(repository.isExternalSignalProcessed(runId, tenantA, idempotencyKey).await().indefinitely());
        assertFalse(repository.isExternalSignalProcessed(runId, tenantB, idempotencyKey).await().indefinitely());
        assertTrue(repository.markExternalSignalProcessed(runId, tenantB, idempotencyKey).await().indefinitely());
        assertFalse(repository.markExternalSignalProcessed(runId, tenantA, idempotencyKey).await().indefinitely());
    }

    @Test
    default void idempotencyMarkers_externalSignalMarkersHonorLegacyGlobalMarkersAndRejectBlankKeys() {
        ExecutionHistoryRepository repository = newExecutionHistoryRepository();
        WorkflowRunId runId = WorkflowRunId.of("contract-legacy-external-signal-marker-run");
        TenantId tenant = TenantId.of("tenant-a");
        String idempotencyKey = "legacy-signal-token-hash";

        assertFalse(repository.isExternalSignalProcessed(runId, "").await().indefinitely());
        assertFalse(repository.isExternalSignalProcessed(runId, tenant, " ").await().indefinitely());
        assertFalse(repository.markExternalSignalProcessed(runId, "").await().indefinitely());
        assertFalse(repository.markExternalSignalProcessed(runId, tenant, " ").await().indefinitely());

        assertTrue(repository.markExternalSignalProcessed(runId, idempotencyKey).await().indefinitely());
        assertTrue(repository.isExternalSignalProcessed(runId, tenant, idempotencyKey).await().indefinitely());
        assertFalse(repository.markExternalSignalProcessed(runId, tenant, idempotencyKey).await().indefinitely());
        assertFalse(repository.markExternalSignalProcessed(runId, idempotencyKey).await().indefinitely());
    }
}
