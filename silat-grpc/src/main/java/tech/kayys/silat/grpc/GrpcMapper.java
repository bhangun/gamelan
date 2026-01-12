package tech.kayys.silat.grpc;

import com.google.protobuf.Struct;
import com.google.protobuf.Timestamp;
import java.time.Instant;
import java.util.Map;

import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tech.kayys.silat.execution.ExecutionHistory;
import tech.kayys.silat.execution.NodeExecutionResult;
import tech.kayys.silat.execution.ExecutionContext;
import tech.kayys.silat.execution.ExecutionError;
import tech.kayys.silat.execution.NodeExecutionStatus;
import tech.kayys.silat.grpc.v1.*;
import tech.kayys.silat.model.ErrorInfo;
import tech.kayys.silat.model.ExecutionToken;
import tech.kayys.silat.model.NodeId;
import tech.kayys.silat.model.WorkflowRun;
import tech.kayys.silat.model.WorkflowRunId;
import tech.kayys.silat.model.WorkflowRunSnapshot;

/**
 * Maps between domain objects and Protocol Buffer messages
 */
@jakarta.enterprise.context.ApplicationScoped
public class GrpcMapper {

    private static final Logger LOG = LoggerFactory.getLogger(GrpcMapper.class);

    @Inject
    com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    // ==================== RUN RESPONSE MAPPING ====================

    public RunResponse toProtoRunResponse(
            WorkflowRun run) {

        WorkflowRunSnapshot snapshot = run.createSnapshot();

        RunResponse.Builder builder = RunResponse.newBuilder()
                .setRunId(snapshot.id().value())
                .setTenantId(snapshot.tenantId().value())
                .setWorkflowDefinitionId(snapshot.definitionId().value())
                .setStatus(toProtoRunStatus(snapshot.status()))
                .setCreatedAt(toProtoTimestamp(snapshot.createdAt()));

        if (snapshot.startedAt() != null) {
            builder.setStartedAt(toProtoTimestamp(snapshot.startedAt()));
        }

        if (snapshot.completedAt() != null) {
            builder.setCompletedAt(toProtoTimestamp(snapshot.completedAt()));

            long durationMs = java.time.Duration.between(
                    snapshot.startedAt(),
                    snapshot.completedAt()).toMillis();
            builder.setDurationMs(durationMs);
        }

        // Add variables
        if (snapshot.variables() != null) {
            builder.setVariables(mapToStruct(snapshot.variables()));
        }

        // Add node executions
        snapshot.nodeExecutions().forEach((nodeId, exec) -> {
            builder.putNodeExecutions(
                    nodeId.value(),
                    toProtoNodeExecution(exec));
        });

        // Add execution path
        builder.addAllExecutionPath(snapshot.executionPath());

        return builder.build();
    }

    // ==================== NODE EXECUTION MAPPING ====================

    public NodeExecution toProtoNodeExecution(
            tech.kayys.silat.model.NodeExecution exec) {

        NodeExecution.Builder builder = NodeExecution.newBuilder()
                .setNodeId(exec.getNodeId().value())
                .setStatus(exec.getStatus().name())
                .setAttempt(exec.getAttempt());

        if (exec.getOutput() != null && !exec.getOutput().isEmpty()) {
            builder.setOutput(mapToStruct(exec.getOutput()));
        }

        if (exec.getLastError() != null) {
            builder.setError(toProtoErrorInfo(exec.getLastError()));
        }

        return builder.build();
    }

    // ==================== ERROR INFO MAPPING ====================

    public tech.kayys.silat.grpc.v1.ErrorInfo toProtoErrorInfo(
            tech.kayys.silat.model.ErrorInfo error) {

        return tech.kayys.silat.grpc.v1.ErrorInfo.newBuilder()
                .setCode(error.code())
                .setMessage(error.message())
                .setStackTrace(error.stackTrace())
                .setContext(mapToStruct(error.context()))
                .build();
    }

    // ==================== HISTORY MAPPING ====================

    public ExecutionHistoryResponse toProtoHistoryResponse(
            ExecutionHistory history) {

        ExecutionHistoryResponse.Builder builder = ExecutionHistoryResponse.newBuilder()
                .setRunId(history.getRunId().value())
                .setTotalEvents(history.getEvents().size());

        history.getEvents().forEach(event -> {
            builder.addEvents(toProtoExecutionEvent(event));
        });

        return builder.build();
    }

    public ExecutionEvent toProtoExecutionEvent(
            tech.kayys.silat.execution.ExecutionHistory.ExecutionEventHistory event) {

        return ExecutionEvent.newBuilder()
                .setEventId(event.getEventId())
                .setEventType(event.getEventType().name())
                .setOccurredAt(toProtoTimestamp(event.getTimestamp()))
                .build();
    }

    // ==================== NODE RESULT MAPPING ====================

    public NodeExecutionResult toDomainNodeResult(
            TaskResult protoResult) {

        return new SimpleNodeExecutionResult(
                WorkflowRunId.of(protoResult.getRunId()),
                NodeId.of(protoResult.getNodeId()),
                protoResult.getAttempt(),
                toDomainTaskStatus(protoResult.getStatus()),
                structToMap(protoResult.getOutput()),
                protoResult.hasError() ? toDomainErrorInfo(protoResult.getError()) : null,
                new ExecutionToken(
                        protoResult.getExecutionToken(),
                        WorkflowRunId.of(protoResult.getRunId()),
                        NodeId.of(protoResult.getNodeId()),
                        protoResult.getAttempt(),
                        Instant.now().plusSeconds(3600)),
                Instant.now(),
                java.time.Duration.ZERO,
                Map.of(),
                null);
    }

    private record SimpleNodeExecutionResult(
            WorkflowRunId runId,
            NodeId nodeId,
            int attempt,
            NodeExecutionStatus statusVal, // Renamed to avoid conflict with getStatus
            Map<String, Object> updatedContextVal,
            tech.kayys.silat.model.ErrorInfo errorVal,
            ExecutionToken executionToken,
            Instant executedAtVal,
            java.time.Duration durationVal,
            Map<String, String> metadataVal,
            tech.kayys.silat.model.WaitInfo waitInfoVal) implements NodeExecutionResult {

        // Fluent accessors (provided by record components):
        // runId(), attempt(), executionToken()

        // Getter accessors (implemented explicitly):

        @Override
        public String getNodeId() {
            return nodeId.value();
        }

        @Override
        public NodeExecutionStatus getStatus() {
            return statusVal;
        }

        @Override
        public ExecutionContext getUpdatedContext() {
            return ExecutionContext.builder()
                    .runId(runId)
                    .variables(updatedContextVal)
                    .build();
        }

        @Override
        public ExecutionError getError() {
            if (errorVal == null) return null;
            return new ExecutionError() {
                @Override public String getCode() { return errorVal.code(); }
                @Override public Category getCategory() { return Category.SYSTEM; }
                @Override public String getMessage() { return errorVal.message(); }
                @Override public boolean isRetriable() { return false; }
                @Override public String getCompensationHint() { return null; }
                @Override public Map<String, Object> getDetails() { return errorVal.context(); }
            };
        }

        @Override
        public Instant getExecutedAt() {
            return executedAtVal;
        }

        @Override
        public java.time.Duration getDuration() {
            return durationVal;
        }

        @Override
        public Map<String, Object> getMetadata() {
            return new java.util.HashMap<>(metadataVal);
        }

        @Override
        public tech.kayys.silat.model.WaitInfo getWaitInfo() {
            return waitInfoVal;
        }
    }

    // ==================== UTILITY METHODS ====================

    public Timestamp toProtoTimestamp(Instant instant) {
        return Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond())
                .setNanos(instant.getNano())
                .build();
    }

    public Instant toInstant(Timestamp timestamp) {
        return Instant.ofEpochSecond(
                timestamp.getSeconds(),
                timestamp.getNanos());
    }

    public RunStatus toProtoRunStatus(
            tech.kayys.silat.model.RunStatus status) {
        return RunStatus.valueOf(
                "RUN_STATUS_" + status.name());
    }

    public NodeExecutionStatus toDomainTaskStatus(
            TaskStatus status) {
        return NodeExecutionStatus.valueOf(
                status.name().replace("TASK_STATUS_", ""));
    }

    public Struct mapToStruct(Map<String, Object> map) {
        try {
            String json = objectMapper.writeValueAsString(map);
            Struct.Builder builder = Struct.newBuilder();
            com.google.protobuf.util.JsonFormat.parser()
                    .merge(json, builder);
            return builder.build();
        } catch (Exception e) {
            LOG.error("Failed to convert map to Struct", e);
            return Struct.getDefaultInstance();
        }
    }

    public Map<String, Object> structToMap(Struct struct) {
        try {
            String json = com.google.protobuf.util.JsonFormat.printer()
                    .print(struct);
            return objectMapper.readValue(json,
                    new com.fasterxml.jackson.core.type.TypeReference<>() {
                    });
        } catch (Exception e) {
            LOG.error("Failed to convert Struct to map", e);
            return Map.of();
        }
    }

    public tech.kayys.silat.model.ErrorInfo toDomainErrorInfo(
            tech.kayys.silat.grpc.v1.ErrorInfo error) {
        return new tech.kayys.silat.model.ErrorInfo(
                error.getCode(),
                error.getMessage(),
                error.getStackTrace(),
                structToMap(error.getContext()));
    }
}
