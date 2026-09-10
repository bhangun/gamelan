package tech.kayys.gamelan.engine.grpc;

import io.quarkus.grpc.GrpcService;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tech.kayys.gamelan.engine.workflow.WorkflowRunManager;
import tech.kayys.gamelan.dispatcher.GrpcTaskStreamBroker;
import tech.kayys.gamelan.registry.ExecutorRegistryService;
import tech.kayys.gamelan.engine.executor.ExecutorInfo;
import tech.kayys.gamelan.engine.node.NodeExecutionResult;
import tech.kayys.gamelan.engine.node.NodeResultHandlingOutcome;
import tech.kayys.gamelan.grpc.GrpcMapper;
import tech.kayys.gamelan.grpc.v1.*;

import com.google.protobuf.Empty;
import java.time.Instant;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * gRPC service for executor communication
 */
@GrpcService
public class ExecutorServiceImpl implements ExecutorService {

    private static final Logger LOG = LoggerFactory.getLogger(ExecutorServiceImpl.class);

    @Inject
    ExecutorRegistryService executorRegistry;

    @Inject
    WorkflowRunManager runManager;

    @Inject
    GrpcMapper mapper;

    @Inject
    GrpcTaskStreamBroker taskStreamBroker;

    // ==================== REGISTER EXECUTOR ====================

    // NOTE: If using strict gRPC, method names must match proto service.
    // Quarkus gRPC detects methods by name matching proto service methods.

    public Uni<ExecutorRegistration> registerExecutor(
            RegisterExecutorRequest request) {

        LOG.info("gRPC: Registering executor: {}", request.getExecutorId());

        ExecutorInfo executor = new ExecutorInfo(
                request.getExecutorId(),
                executorType(request),
                tech.kayys.gamelan.engine.protocol.CommunicationType.GRPC,
                request.getEndpoint(),
                Duration.ofSeconds(30), // Default heartbeat interval
                registrationMetadata(request));

        return executorRegistry.registerExecutor(executor)
                .replaceWith(ExecutorRegistration.newBuilder()
                        .setExecutorId(request.getExecutorId())
                        .setStatus("REGISTERED")
                        .setRegisteredAt(mapper.toProtoTimestamp(Instant.now()))
                        .build());
    }

    // ==================== UNREGISTER EXECUTOR ====================

    public Uni<Empty> unregisterExecutor(
            UnregisterExecutorRequest request) {

        LOG.info("gRPC: Unregistering executor: {}", request.getExecutorId());
        return executorRegistry.unregisterExecutor(request.getExecutorId())
                .replaceWith(Empty.getDefaultInstance());
    }

    // ==================== HEARTBEAT ====================

    public Uni<Empty> heartbeat(HeartbeatRequest request) {
        // LOG.debug("gRPC: Heartbeat from: {}", request.getExecutorId());
        return executorRegistry.heartbeat(request.getExecutorId(), request.getCurrentTaskCount())
                .replaceWith(Empty.getDefaultInstance());
    }

    // ==================== STREAM TASKS (SERVER STREAMING) ====================

    @Override
    public Multi<ExecutionTask> streamTasks(
            StreamTasksRequest request) {

        LOG.info("gRPC: Starting task stream for executor: {}",
                request.getExecutorId());

        if (taskStreamBroker == null) {
            LOG.warn("gRPC task stream requested, but no task stream broker is available");
            return Multi.createFrom().empty();
        }

        return taskStreamBroker.stream(request.getExecutorId(), request.getMaxConcurrent())
                .onItem().transform(task -> mapper.toProtoExecutionTask(task.taskId(), task.task()));
    }

    // ==================== ACKNOWLEDGE TASK ====================

    @Override
    public Uni<Empty> acknowledgeTask(TaskAcknowledgement request) {
        LOG.trace("gRPC: Task acknowledged: {}", request.getTaskId());
        return acknowledgeTask(request.getTaskId())
                .replaceWith(Empty.getDefaultInstance());
    }

    // ==================== REPORT RESULTS (CLIENT STREAMING) ====================

    @Override
    public Uni<Empty> reportResults(Multi<TaskResult> results) {

        LOG.info("gRPC: Receiving task results stream");

        return results
                .onItem().transformToUniAndConcatenate(this::handleTaskResult)
                .collect().asList()
                .replaceWith(Empty.getDefaultInstance());
    }

    // ==================== EXECUTE STREAM (BIDIRECTIONAL) ====================

    @Override
    public Multi<EngineMessage> executeStream(
            Multi<ExecutorMessage> request) {

        LOG.info("gRPC: Starting bidirectional stream");

        return request.onItem().transformToUniAndConcatenate(message -> {
            if (message.hasHeartbeat()) {
                LOG.trace("Heartbeat from executor: {}", message.getHeartbeat().getExecutorId());
                return heartbeat(message.getHeartbeat()).replaceWith(EngineMessage.getDefaultInstance());
            } else if (message.hasResult()) {
                LOG.debug("Result from executor: {}", message.getResult().getTaskId());
                return handleTaskResult(message.getResult()).replaceWith(EngineMessage.getDefaultInstance());
            } else if (message.hasAck()) {
                LOG.trace("Task acknowledged: {}", message.getAck().getTaskId());
                return acknowledgeTask(message.getAck().getTaskId())
                        .replaceWith(EngineMessage.getDefaultInstance());
            }
            return Uni.createFrom().item(EngineMessage.getDefaultInstance());
        });
    }

    private Uni<Void> handleTaskResult(TaskResult result) {
        NodeExecutionResult domainResult = mapper.toDomainNodeResult(result);
        Uni<NodeResultHandlingOutcome> completion = mapper.toTenantId(result)
                .map(tenantId -> runManager.onNodeExecutionCompletedWithOutcome(
                        domainResult,
                        tenantId,
                        result.getExecutionToken()))
                .orElseGet(() -> runManager.onNodeExecutionCompletedWithOutcome(
                        domainResult,
                        result.getExecutionToken()));
        return completion
                .call(outcome -> completeStreamTask(result))
                .invoke(outcome -> LOG.debug(
                        "Result processed: task={}, acceptance={}, duplicate={}, runUpdated={}",
                        result.getTaskId(),
                        outcome.acceptance(),
                        outcome.duplicate(),
                        outcome.runUpdated()))
                .replaceWithVoid()
                .onFailure().invoke(error -> LOG.error("Failed to process result: {}", result.getTaskId(), error));
    }

    private Uni<Void> acknowledgeTask(String taskId) {
        return taskStreamBroker != null
                ? taskStreamBroker.acknowledge(taskId)
                : Uni.createFrom().voidItem();
    }

    private Uni<Void> completeStreamTask(TaskResult result) {
        if (taskStreamBroker == null) {
            return Uni.createFrom().voidItem();
        }
        return taskStreamBroker.complete(mapper.toTaskId(result));
    }

    private String executorType(RegisterExecutorRequest request) {
        if (request.getExecutorType() != null && !request.getExecutorType().isBlank()) {
            return request.getExecutorType();
        }
        if (request.getSupportedNodeTypesCount() > 0) {
            return request.getSupportedNodeTypes(0);
        }
        return "unspecified";
    }

    private Map<String, String> registrationMetadata(RegisterExecutorRequest request) {
        Map<String, String> metadata = new HashMap<>(request.getMetadataMap());
        if (request.getSupportedNodeTypesCount() > 0) {
            metadata.putIfAbsent("gamelan.supported-node-types",
                    String.join(",", request.getSupportedNodeTypesList()));
        }
        if (request.getMaxConcurrentTasks() > 0) {
            metadata.putIfAbsent(tech.kayys.gamelan.registry.LeastLoadedSelectionStrategy.METADATA_MAX_CONCURRENT_TASKS,
                    Integer.toString(request.getMaxConcurrentTasks()));
        }
        if (request.getEndpoint() == null || request.getEndpoint().isBlank()) {
            metadata.putIfAbsent(tech.kayys.gamelan.dispatcher.GrpcStreamTaskDispatcher.METADATA_GRPC_DELIVERY,
                    "stream");
        }
        return metadata;
    }
}
