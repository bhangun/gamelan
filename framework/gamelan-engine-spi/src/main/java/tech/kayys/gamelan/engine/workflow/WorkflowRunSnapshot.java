package tech.kayys.gamelan.engine.workflow;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import tech.kayys.gamelan.engine.node.NodeExecution;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.payload.ExecutionPayloads;
import tech.kayys.gamelan.engine.run.RunStatus;
import tech.kayys.gamelan.engine.run.SuspensionInfo;
import tech.kayys.gamelan.engine.saga.CompensationState;
import tech.kayys.gamelan.engine.signal.Signal;
import tech.kayys.gamelan.engine.tenant.TenantId;

/**
 * Workflow Run Snapshot - Point-in-time state
 */
public record WorkflowRunSnapshot(
                WorkflowRunId id,
                TenantId tenantId,
                WorkflowDefinitionId definitionId,
                String definitionVersion,
                RunStatus status,
                Map<String, Object> variables,
                Map<NodeId, NodeExecution> nodeExecutions,
                List<String> executionPath,
                SuspensionInfo suspensionInfo,
                Map<String, Signal> pendingSignals,
                CompensationState compensationState,
                Instant createdAt,
                Instant startedAt,
                Instant completedAt,
                long version) {
    public WorkflowRunSnapshot(
            WorkflowRunId id,
            TenantId tenantId,
            WorkflowDefinitionId definitionId,
            RunStatus status,
            Map<String, Object> variables,
            Map<NodeId, NodeExecution> nodeExecutions,
            List<String> executionPath,
            SuspensionInfo suspensionInfo,
            Map<String, Signal> pendingSignals,
            CompensationState compensationState,
            Instant createdAt,
            Instant startedAt,
            Instant completedAt,
            long version) {
        this(
                id,
                tenantId,
                definitionId,
                "unknown",
                status,
                variables,
                nodeExecutions,
                executionPath,
                suspensionInfo,
                pendingSignals,
                compensationState,
                createdAt,
                startedAt,
                completedAt,
                version);
    }

    public WorkflowRunSnapshot {
        definitionVersion = definitionVersion == null || definitionVersion.isBlank() ? "unknown" : definitionVersion;
        variables = ExecutionPayloads.immutableMap(variables);
        nodeExecutions = copyNodeExecutions(nodeExecutions);
        executionPath = executionPath != null ? List.copyOf(executionPath) : List.of();
        pendingSignals = pendingSignals != null ? Map.copyOf(new HashMap<>(pendingSignals)) : Map.of();
    }

    private static Map<NodeId, NodeExecution> copyNodeExecutions(Map<NodeId, NodeExecution> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }

        Map<NodeId, NodeExecution> copy = new HashMap<>();
        source.forEach((nodeId, execution) -> copy.put(nodeId, NodeExecution.copyOf(execution)));
        return Map.copyOf(copy);
    }
}
