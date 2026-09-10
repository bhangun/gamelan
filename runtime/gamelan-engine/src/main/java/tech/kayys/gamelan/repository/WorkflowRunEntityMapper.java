package tech.kayys.gamelan.repository;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import tech.kayys.gamelan.domain.WorkflowRunEntity;
import tech.kayys.gamelan.engine.error.ErrorCode;
import tech.kayys.gamelan.engine.error.ErrorInfo;
import tech.kayys.gamelan.engine.error.ErrorSnapshot;
import tech.kayys.gamelan.engine.error.GamelanException;
import tech.kayys.gamelan.engine.node.NodeDefinition;
import tech.kayys.gamelan.engine.node.NodeExecution;
import tech.kayys.gamelan.engine.node.NodeExecutionSnapshot;
import tech.kayys.gamelan.engine.node.NodeExecutionStatus;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.run.RunStatus;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinition;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinitionId;
import tech.kayys.gamelan.engine.workflow.WorkflowRun;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunSnapshot;

final class WorkflowRunEntityMapper {

    private WorkflowRunEntityMapper() {
    }

    static WorkflowRunEntity toEntity(WorkflowRun run) {
        WorkflowRunSnapshot snapshot = run.createSnapshot();
        WorkflowRunEntity entity = new WorkflowRunEntity();
        entity.setRunId(snapshot.id().value());
        entity.setTenantId(snapshot.tenantId().value());
        entity.setDefinitionId(snapshot.definitionId().value());
        entity.setDefinitionVersion(snapshot.definitionVersion());
        entity.setStatus(snapshot.status());
        entity.setContextVariables(new HashMap<>(snapshot.variables()));
        entity.setNodeExecutions(toNodeSnapshots(snapshot.nodeExecutions()));
        entity.setExecutionPath(List.copyOf(snapshot.executionPath()));
        entity.setSuspensionInfo(snapshot.suspensionInfo());
        entity.setPendingSignals(new HashMap<>(snapshot.pendingSignals()));
        entity.setCompensationState(snapshot.compensationState());
        entity.setCreatedAt(snapshot.createdAt());
        entity.setStartedAt(snapshot.startedAt());
        entity.setCompletedAt(snapshot.completedAt());
        entity.setLastUpdatedAt(Instant.now());
        entity.setVersion(snapshot.version());
        return entity;
    }

    static WorkflowRun toDomain(WorkflowRunEntity entity, WorkflowDefinition definition) {
        if (entity == null) {
            return null;
        }

        TenantId tenantId = TenantId.of(entity.getTenantId());
        WorkflowDefinitionId definitionId = WorkflowDefinitionId.of(entity.getDefinitionId());
        if (!tenantId.equals(definition.tenantId()) || !definitionId.equals(definition.id())) {
            throw new GamelanException(
                    ErrorCode.STORAGE_SERIALIZATION_FAILED,
                    "Workflow run row does not match loaded workflow definition");
        }

        WorkflowRunSnapshot snapshot = new WorkflowRunSnapshot(
                WorkflowRunId.of(entity.getRunId()),
                tenantId,
                definitionId,
                entity.getDefinitionVersion() != null ? entity.getDefinitionVersion() : "unknown",
                entity.getStatus() != null ? entity.getStatus() : RunStatus.CREATED,
                entity.getContextVariables() != null ? entity.getContextVariables() : Map.of(),
                toNodeExecutions(entity.getNodeExecutions(), definition),
                entity.getExecutionPath() != null ? entity.getExecutionPath() : List.of(),
                entity.getSuspensionInfo(),
                entity.getPendingSignals() != null ? entity.getPendingSignals() : Map.of(),
                entity.getCompensationState(),
                entity.getCreatedAt() != null ? entity.getCreatedAt() : Instant.now(),
                entity.getStartedAt(),
                entity.getCompletedAt(),
                entity.getVersion() != null ? entity.getVersion() : 0L);

        return WorkflowRun.restore(snapshot, definition);
    }

    private static Map<String, NodeExecutionSnapshot> toNodeSnapshots(Map<NodeId, NodeExecution> executions) {
        Map<String, NodeExecutionSnapshot> nodeSnapshots = new HashMap<>();
        if (executions == null) {
            return nodeSnapshots;
        }

        executions.forEach((nodeId, execution) -> nodeSnapshots.put(nodeId.value(), new NodeExecutionSnapshot(
                nodeId.value(),
                execution.getStatus().name(),
                execution.getAttempt(),
                execution.getStartedAt(),
                execution.getCompletedAt(),
                execution.getRetryAt(),
                execution.getOutput(),
                toErrorSnapshot(execution.getLastError()))));
        return nodeSnapshots;
    }

    private static Map<NodeId, NodeExecution> toNodeExecutions(
            Map<String, NodeExecutionSnapshot> snapshots,
            WorkflowDefinition definition) {
        Map<NodeId, NodeExecution> executions = new HashMap<>();
        if (snapshots == null) {
            return executions;
        }

        snapshots.forEach((key, snapshot) -> {
            NodeId nodeId = NodeId.of(snapshot.nodeId() != null ? snapshot.nodeId() : key);
            NodeDefinition nodeDefinition = definition.findNode(nodeId)
                    .orElseThrow(() -> new GamelanException(
                            ErrorCode.TASK_NOT_FOUND,
                            "Node not found in workflow definition: " + nodeId.value()));

            NodeExecution execution = NodeExecution.create(nodeId, nodeDefinition);
            execution.setStatus(toStatus(snapshot.status()));
            execution.setAttempt(snapshot.attempt() > 0 ? snapshot.attempt() : 1);
            execution.setStartedAt(snapshot.startedAt());
            execution.setCompletedAt(snapshot.completedAt());
            execution.setRetryAt(snapshot.retryAt());
            execution.setOutput(snapshot.output());
            execution.setLastError(toErrorInfo(snapshot.error()));
            executions.put(nodeId, execution);
        });
        return executions;
    }

    private static NodeExecutionStatus toStatus(String status) {
        if (status == null || status.isBlank()) {
            return NodeExecutionStatus.PENDING;
        }
        try {
            return NodeExecutionStatus.valueOf(status);
        } catch (IllegalArgumentException ex) {
            throw new GamelanException(
                    ErrorCode.STORAGE_SERIALIZATION_FAILED,
                    "Unknown node execution status: " + status,
                    ex);
        }
    }

    private static ErrorSnapshot toErrorSnapshot(ErrorInfo error) {
        if (error == null) {
            return null;
        }
        return new ErrorSnapshot(error.code(), error.message(), error.stackTrace());
    }

    private static ErrorInfo toErrorInfo(ErrorSnapshot error) {
        if (error == null) {
            return null;
        }
        return new ErrorInfo(error.code(), error.message(), error.stackTrace(), Map.of());
    }
}
