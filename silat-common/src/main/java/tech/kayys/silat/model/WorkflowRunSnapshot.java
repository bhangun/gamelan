package tech.kayys.silat.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Workflow Run Snapshot - Point-in-time state
 */
public record WorkflowRunSnapshot(
        WorkflowRunId id,
        TenantId tenantId,
        WorkflowDefinitionId definitionId,
        RunStatus status,
        Map<String, Object> variables,
        Map<NodeId, NodeExecution> nodeExecutions,
        List<String> executionPath,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt,
        long version) {
}
