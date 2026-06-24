package tech.kayys.gamelan.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tech.kayys.gamelan.engine.error.ErrorCode;
import tech.kayys.gamelan.engine.error.GamelanException;
import tech.kayys.gamelan.engine.execution.ExecutionToken;
import tech.kayys.gamelan.engine.node.NodeExecutionResult;
import tech.kayys.gamelan.engine.node.NodeExecutionTask;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.run.RetryPolicy;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;
import tech.kayys.gamelan.grpc.v1.ExecutionTask;
import tech.kayys.gamelan.grpc.v1.TaskResult;
import tech.kayys.gamelan.grpc.v1.TaskStatus;

class GrpcMapperTest {

    private static final WorkflowRunId RUN_ID = WorkflowRunId.of("run-1");
    private static final NodeId NODE_ID = NodeId.of("node-1");

    private GrpcMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new GrpcMapper();
        mapper.objectMapper = new ObjectMapper();
    }

    @Test
    void toProtoExecutionTaskUsesNodeConfigurationSnapshotForConfiguration() {
        Map<String, Object> context = new HashMap<>();
        context.put("__node_type__", "script");
        context.put(NodeExecutionTask.TENANT_ID_KEY, "tenant-a");
        context.put("runtimeOnly", "visible-in-context");
        context.put(NodeExecutionTask.TIMEOUT_SECONDS_KEY, 45);
        context.put(NodeExecutionTask.WORKFLOW_VARIABLES_KEY, Map.of("topic", "orders"));
        context.put(NodeExecutionTask.NODE_CONFIGURATION_KEY, Map.of(
                "__node_type__", "script",
                "language", "javascript"));

        ExecutionTask proto = mapper.toProtoExecutionTask("task-1", task(context));

        Map<String, Object> protoContext = mapper.structToMap(proto.getContext());
        Map<String, Object> protoConfiguration = mapper.structToMap(proto.getConfiguration());
        assertEquals(
                "orders",
                ((Map<?, ?>) protoContext.get(NodeExecutionTask.WORKFLOW_VARIABLES_KEY)).get("topic"));
        assertEquals("visible-in-context", protoContext.get("runtimeOnly"));
        assertEquals("javascript", protoConfiguration.get("language"));
        assertFalse(protoConfiguration.containsKey(NodeExecutionTask.WORKFLOW_VARIABLES_KEY));
        assertEquals("tenant-a", proto.getTenantId());
        assertEquals(45L, proto.getTimeoutSeconds());
    }

    @Test
    void toTenantIdMapsOptionalTaskResultTenant() {
        TaskResult tenantAware = TaskResult.newBuilder()
                .setTenantId("tenant-a")
                .build();
        TaskResult legacy = TaskResult.newBuilder().build();

        assertEquals(TenantId.of("tenant-a"), mapper.toTenantId(tenantAware).orElseThrow());
        assertTrue(mapper.toTenantId(legacy).isEmpty());
    }

    @Test
    void toProtoExecutionTaskFallsBackToFullContextForLegacyTasksWithoutConfigurationSnapshot() {
        Map<String, Object> context = Map.of(
                "__node_type__", "http",
                "url", "https://example.test");

        ExecutionTask proto = mapper.toProtoExecutionTask("task-1", task(context));

        Map<String, Object> protoConfiguration = mapper.structToMap(proto.getConfiguration());
        assertEquals("https://example.test", protoConfiguration.get("url"));
        assertTrue(protoConfiguration.containsKey("__node_type__"));
    }

    @Test
    void toTaskIdUsesProvidedTaskResultIdWhenPresent() {
        TaskResult result = TaskResult.newBuilder()
                .setTaskId("custom-task-id")
                .setRunId("run-1")
                .setNodeId("node-1")
                .setAttempt(1)
                .build();

        assertEquals("custom-task-id", mapper.toTaskId(result));
    }

    @Test
    void toTaskIdFallsBackToStableTaskIdentityForTaskResultsWithoutTaskId() {
        TaskResult result = TaskResult.newBuilder()
                .setRunId("run-1")
                .setNodeId("node-1")
                .setAttempt(3)
                .build();

        assertEquals("run-1:node-1:3", mapper.toTaskId(result));
    }

    @Test
    void toDomainNodeResultRejectsBlankNodeId() {
        TaskResult result = TaskResult.newBuilder()
                .setRunId("run-1")
                .setNodeId("   ")
                .setAttempt(1)
                .build();

        GamelanException error = assertThrows(GamelanException.class, () -> mapper.toDomainNodeResult(result));

        assertEquals(ErrorCode.VALIDATION_FAILED, error.getErrorCode());
        assertEquals("NodeId cannot be blank", error.getSafeMessage());
    }

    @Test
    void toDomainNodeResultRejectsNonPositiveAttempt() {
        TaskResult result = TaskResult.newBuilder()
                .setRunId("run-1")
                .setNodeId("node-1")
                .setAttempt(0)
                .build();

        GamelanException error = assertThrows(GamelanException.class, () -> mapper.toDomainNodeResult(result));

        assertEquals(ErrorCode.VALIDATION_FAILED, error.getErrorCode());
        assertEquals("TaskResult attempt must be positive", error.getSafeMessage());
    }

    @Test
    void toDomainNodeResultRejectsUnspecifiedStatus() {
        TaskResult result = TaskResult.newBuilder()
                .setRunId("run-1")
                .setNodeId("node-1")
                .setAttempt(1)
                .setStatus(TaskStatus.TASK_STATUS_UNSPECIFIED)
                .build();

        GamelanException error = assertThrows(GamelanException.class, () -> mapper.toDomainNodeResult(result));

        assertEquals(ErrorCode.VALIDATION_FAILED, error.getErrorCode());
        assertEquals("TaskResult status must be specified", error.getSafeMessage());
    }

    @Test
    void toDomainNodeResultPreservesTenantOnExecutionToken() {
        TaskResult result = TaskResult.newBuilder()
                .setRunId("run-1")
                .setTenantId("tenant-a")
                .setNodeId("node-1")
                .setAttempt(1)
                .setStatus(TaskStatus.TASK_STATUS_COMPLETED)
                .setExecutionToken("execution-token")
                .build();

        NodeExecutionResult domain = mapper.toDomainNodeResult(result);

        assertEquals(TenantId.of("tenant-a"), domain.executionToken().tenantId());
    }

    @Test
    @SuppressWarnings("unchecked")
    void toDomainNodeResultFreezesMappedOutput() {
        TaskResult result = TaskResult.newBuilder()
                .setRunId("run-1")
                .setNodeId("node-1")
                .setAttempt(1)
                .setStatus(TaskStatus.TASK_STATUS_COMPLETED)
                .setOutput(mapper.mapToStruct(Map.of("nested", Map.of("answer", 42))))
                .build();

        NodeExecutionResult domain = mapper.toDomainNodeResult(result);

        assertNull(domain.executionToken());
        assertEquals(42.0, ((Map<String, Object>) domain.output().get("nested")).get("answer"));
        assertThrows(UnsupportedOperationException.class, () -> domain.output().put("x", "y"));
        assertThrows(UnsupportedOperationException.class,
                () -> ((Map<String, Object>) domain.output().get("nested")).put("x", "y"));
    }

    private static NodeExecutionTask task(Map<String, Object> context) {
        return new NodeExecutionTask(
                RUN_ID,
                NODE_ID,
                1,
                new ExecutionToken("token-1", RUN_ID, NODE_ID, 1, Instant.now().plusSeconds(60)),
                context,
                RetryPolicy.none());
    }
}
