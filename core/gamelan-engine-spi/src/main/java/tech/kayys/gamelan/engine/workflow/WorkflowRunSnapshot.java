package tech.kayys.gamelan.engine.workflow;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import tech.kayys.gamelan.engine.node.NodeExecution;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.run.RunStatus;
import tech.kayys.gamelan.engine.tenant.TenantId;

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
