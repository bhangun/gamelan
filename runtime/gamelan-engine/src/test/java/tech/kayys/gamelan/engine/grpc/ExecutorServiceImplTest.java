package tech.kayys.gamelan.engine.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.dispatcher.GrpcTaskStreamBroker;
import tech.kayys.gamelan.dispatcher.InMemoryGrpcTaskStreamBroker;
import tech.kayys.gamelan.engine.execution.ExecutionToken;
import tech.kayys.gamelan.engine.node.NodeExecutionResult;
import tech.kayys.gamelan.engine.node.NodeExecutionResults;
import tech.kayys.gamelan.engine.node.NodeExecutionStatus;
import tech.kayys.gamelan.engine.node.NodeExecutionTask;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.node.NodeResultHandlingOutcome;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunManager;
import tech.kayys.gamelan.grpc.GrpcMapper;
import tech.kayys.gamelan.grpc.v1.ExecutorMessage;
import tech.kayys.gamelan.grpc.v1.ExecutionTask;
import tech.kayys.gamelan.grpc.v1.HeartbeatRequest;
import tech.kayys.gamelan.grpc.v1.StreamTasksRequest;
import tech.kayys.gamelan.grpc.v1.TaskAcknowledgement;
import tech.kayys.gamelan.grpc.v1.TaskResult;
import tech.kayys.gamelan.grpc.v1.TaskStatus;
import tech.kayys.gamelan.registry.ExecutorRegistryService;

class ExecutorServiceImplTest {

    private ExecutorServiceImpl service;
    private RecordingRunManager runManager;
    private RecordingRegistry registry;

    @BeforeEach
    void setUp() throws Exception {
        runManager = new RecordingRunManager();
        registry = new RecordingRegistry();
        service = new ExecutorServiceImpl();
        service.runManager = runManager.proxy();
        service.executorRegistry = registry.proxy();
        service.mapper = mapper();
        service.taskStreamBroker = new InMemoryGrpcTaskStreamBroker();
    }

    @Test
    void streamTasks_emitsAssignedTasksForExecutor() {
        NodeExecutionTask task = task();
        service.taskStreamBroker.assign("executor-1", task).await().indefinitely();

        List<ExecutionTask> streamed = service.streamTasks(StreamTasksRequest.newBuilder()
                        .setExecutorId("executor-1")
                        .setMaxConcurrent(1)
                        .build())
                .select().first(1)
                .collect().asList()
                .await().atMost(Duration.ofSeconds(2));

        assertEquals(1, streamed.size());
        assertEquals("run-1:node-1:1", streamed.getFirst().getTaskId());
        assertEquals("run-1", streamed.getFirst().getRunId());
        assertEquals("node-1", streamed.getFirst().getNodeId());
        assertEquals("Send welcome email", streamed.getFirst().getNodeName());
        assertEquals("email", streamed.getFirst().getNodeType());
        assertEquals("token-1", streamed.getFirst().getExecutionToken());
        assertEquals("tenant-a", streamed.getFirst().getTenantId());
        assertEquals(30, streamed.getFirst().getTimeoutSeconds());
    }

    @Test
    void reportResults_processesResultsThroughRunManager() {
        TaskResult result = completedResult("task-1");

        service.reportResults(Multi.createFrom().item(result)).await().indefinitely();

        assertEquals(List.of(WorkflowRunId.of("run-1")), runManager.runIds);
        assertEquals(NodeId.of("node-1"), runManager.results.getFirst().nodeId());
        assertEquals(1, runManager.results.getFirst().attempt());
        assertEquals(NodeExecutionStatus.COMPLETED, runManager.results.getFirst().status());
        assertEquals(Map.of("answer", "42"), runManager.results.getFirst().output());
        assertEquals(List.of(TenantId.of("tenant-a")), runManager.tenantIds);
        assertEquals(1, runManager.outcomeCalls);
    }

    @Test
    void executeStream_processesResultMessages() {
        TaskResult result = completedResult("task-2");
        ExecutorMessage message = ExecutorMessage.newBuilder()
                .setResult(result)
                .build();

        service.executeStream(Multi.createFrom().item(message))
                .collect().asList()
                .await().indefinitely();

        assertEquals(List.of(WorkflowRunId.of("run-1")), runManager.runIds);
        assertEquals("node-1", runManager.results.getFirst().getNodeId());
    }

    @Test
    void acknowledgeTask_delegatesToStreamBroker() {
        RecordingTaskStreamBroker broker = new RecordingTaskStreamBroker();
        service.taskStreamBroker = broker;

        service.acknowledgeTask(TaskAcknowledgement.newBuilder()
                        .setTaskId("task-1")
                        .build())
                .await().indefinitely();

        assertEquals(List.of("task-1"), broker.acknowledgedTaskIds);
    }

    @Test
    void heartbeat_passesCurrentTaskCountToRegistry() {
        service.heartbeat(HeartbeatRequest.newBuilder()
                        .setExecutorId("executor-1")
                        .setCurrentTaskCount(7)
                        .build())
                .await().indefinitely();

        assertEquals(List.of("executor-1"), registry.executorIds);
        assertEquals(List.of(7), registry.currentTaskCounts);
    }

    private TaskResult completedResult(String taskId) {
        return TaskResult.newBuilder()
                .setTaskId(taskId)
                .setRunId("run-1")
                .setNodeId("node-1")
                .setAttempt(1)
                .setExecutionToken("token-1")
                .setTenantId("tenant-a")
                .setStatus(TaskStatus.TASK_STATUS_COMPLETED)
                .setOutput(Struct.newBuilder()
                        .putFields("answer", Value.newBuilder().setStringValue("42").build())
                        .build())
                .build();
    }

    private NodeExecutionTask task() {
        return new NodeExecutionTask(
                WorkflowRunId.of("run-1"),
                NodeId.of("node-1"),
                1,
                new ExecutionToken(
                        "token-1",
                        WorkflowRunId.of("run-1"),
                        NodeId.of("node-1"),
                        1,
                        Instant.now().plusSeconds(60)),
                Map.of(
                        "__node_name__", "Send welcome email",
                        "__node_type__", "email",
                        "__tenant_id__", "tenant-a",
                        "__timeout_seconds__", 30,
                        "recipient", "user@example.com"),
                null);
    }

    private GrpcMapper mapper() throws Exception {
        GrpcMapper mapper = new GrpcMapper();
        Field field = GrpcMapper.class.getDeclaredField("objectMapper");
        field.setAccessible(true);
        field.set(mapper, new ObjectMapper());
        return mapper;
    }

    private static class RecordingRunManager {
        private final List<WorkflowRunId> runIds = new ArrayList<>();
        private final List<TenantId> tenantIds = new ArrayList<>();
        private final List<NodeExecutionResult> results = new ArrayList<>();
        private int outcomeCalls;

        private WorkflowRunManager proxy() {
            return (WorkflowRunManager) Proxy.newProxyInstance(
                    WorkflowRunManager.class.getClassLoader(),
                    new Class<?>[] { WorkflowRunManager.class },
                    (proxy, method, args) -> {
                        if ("handleNodeResult".equals(method.getName())) {
                            runIds.add((WorkflowRunId) args[0]);
                            results.add((NodeExecutionResult) args[1]);
                            return Uni.createFrom().voidItem();
                        }
                        if ("onNodeExecutionCompleted".equals(method.getName())) {
                            NodeExecutionResult result = (NodeExecutionResult) args[0];
                            runIds.add(result.runId());
                            tenantIds.add(args.length > 2 ? (TenantId) args[1] : null);
                            results.add(result);
                            return Uni.createFrom().voidItem();
                        }
                        if ("onNodeExecutionCompletedWithOutcome".equals(method.getName())) {
                            NodeExecutionResult result = (NodeExecutionResult) args[0];
                            TenantId tenantId = args.length > 2 ? (TenantId) args[1] : null;
                            runIds.add(result.runId());
                            tenantIds.add(tenantId);
                            results.add(result);
                            outcomeCalls++;
                            return Uni.createFrom().item(new NodeResultHandlingOutcome(
                                    result.runId(),
                                    tenantId,
                                    result.nodeId(),
                                    result.attempt(),
                                    NodeExecutionResults.Acceptance.ACCEPT,
                                    true,
                                    true,
                                    true,
                                    false));
                        }
                        throw new UnsupportedOperationException(method.getName() + " is not used by this test");
                    });
        }
    }

    private static class RecordingRegistry {
        private final List<String> executorIds = new ArrayList<>();
        private final List<Integer> currentTaskCounts = new ArrayList<>();

        private ExecutorRegistryService proxy() {
            return (ExecutorRegistryService) Proxy.newProxyInstance(
                    ExecutorRegistryService.class.getClassLoader(),
                    new Class<?>[] { ExecutorRegistryService.class },
                    (proxy, method, args) -> {
                        if ("heartbeat".equals(method.getName()) && args.length == 2) {
                            executorIds.add((String) args[0]);
                            currentTaskCounts.add((Integer) args[1]);
                            return Uni.createFrom().voidItem();
                        }
                        throw new UnsupportedOperationException(method.getName() + " is not used by this test");
                    });
        }
    }

    private static final class RecordingTaskStreamBroker implements GrpcTaskStreamBroker {
        private final List<String> acknowledgedTaskIds = new ArrayList<>();

        @Override
        public Uni<Void> assign(String executorId, NodeExecutionTask task) {
            return Uni.createFrom().voidItem();
        }

        @Override
        public Multi<StreamedTask> stream(String executorId, int maxConcurrent) {
            return Multi.createFrom().empty();
        }

        @Override
        public Uni<Void> acknowledge(String taskId) {
            acknowledgedTaskIds.add(taskId);
            return Uni.createFrom().voidItem();
        }

        @Override
        public Uni<Void> complete(String taskId) {
            return Uni.createFrom().voidItem();
        }
    }
}
