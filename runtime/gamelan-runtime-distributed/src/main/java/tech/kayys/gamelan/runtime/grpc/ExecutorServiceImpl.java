package tech.kayys.gamelan.runtime.grpc;

import com.google.protobuf.Empty;
import io.quarkus.grpc.GrpcService;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tech.kayys.gamelan.dispatcher.GrpcTaskStreamBroker;
import tech.kayys.gamelan.engine.protocol.CommunicationType;
import tech.kayys.gamelan.engine.executor.ExecutorInfo;
import tech.kayys.gamelan.engine.node.NodeExecutionResult;
import tech.kayys.gamelan.engine.node.NodeResultHandlingOutcome;
import tech.kayys.gamelan.engine.workflow.WorkflowRunManager;
import tech.kayys.gamelan.grpc.CommunicationTypeConverter;
import tech.kayys.gamelan.grpc.GrpcMapper;
import tech.kayys.gamelan.grpc.v1.*;
import tech.kayys.gamelan.registry.ExecutorRegistryService;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@GrpcService
public class ExecutorServiceImpl extends MutinyExecutorServiceGrpc.ExecutorServiceImplBase {

    private static final Logger LOG = LoggerFactory.getLogger(ExecutorServiceImpl.class);

    @Inject
    ExecutorRegistryService executorRegistry;

    @Inject
    WorkflowRunManager runManager;

    @Inject
    GrpcMapper mapper;

    @Inject
    GrpcTaskStreamBroker taskStreamBroker;

    @Override
    public Uni<ExecutorRegistration> registerExecutor(RegisterExecutorRequest request) {
        LOG.info("Received registration request from executor: {}", request.getExecutorId());

        ExecutorInfo executorInfo = new ExecutorInfo(
                request.getExecutorId(),
                executorType(request),
                mapCommunicationType(request),
                request.getEndpoint(),
                Duration.ofHours(24),
                registrationMetadata(request)
        );

        return executorRegistry.registerExecutor(executorInfo)
                .map(v -> ExecutorRegistration.newBuilder()
                        .setExecutorId(request.getExecutorId())
                        .setStatus("REGISTERED")
                        .setRegisteredAt(mapper.toProtoTimestamp(Instant.now()))
                        .build());
    }

    @Override
    public Uni<Empty> unregisterExecutor(UnregisterExecutorRequest request) {
        LOG.info("Received unregistration request from executor: {}", request.getExecutorId());
        return executorRegistry.unregisterExecutor(request.getExecutorId())
                .map(v -> Empty.getDefaultInstance());
    }

    @Override
    public Uni<Empty> heartbeat(HeartbeatRequest request) {
        LOG.trace("Received heartbeat from executor: {}", request.getExecutorId());
        return executorRegistry.heartbeat(request.getExecutorId(), request.getCurrentTaskCount())
                .map(v -> Empty.getDefaultInstance());
    }

    @Override
    public Multi<ExecutionTask> streamTasks(StreamTasksRequest request) {
        LOG.info("Executor {} requested task stream", request.getExecutorId());

        if (taskStreamBroker == null) {
            LOG.warn("Executor {} requested task stream, but no task stream broker is available",
                    request.getExecutorId());
            return Multi.createFrom().empty();
        }

        return taskStreamBroker.stream(request.getExecutorId(), request.getMaxConcurrent())
                .onItem().transform(task -> mapper.toProtoExecutionTask(task.taskId(), task.task()));
    }

    @Override
    public Uni<Empty> acknowledgeTask(TaskAcknowledgement request) {
        LOG.trace("Task acknowledged: {}", request.getTaskId());
        return acknowledgeTask(request.getTaskId())
                .replaceWith(Empty.getDefaultInstance());
    }

    @Override
    public Uni<Empty> reportResults(Multi<TaskResult> request) {
        return request
                .onItem().transformToUniAndConcatenate(this::handleTaskResult)
                .collect().asList()
                .replaceWith(Empty.getDefaultInstance());
    }

    @Override
    public Multi<EngineMessage> executeStream(Multi<ExecutorMessage> request) {
        return request.onItem().transformToUniAndConcatenate(message -> {
            if (message.hasHeartbeat()) {
                return heartbeat(message.getHeartbeat()).replaceWith(EngineMessage.getDefaultInstance());
            }
            if (message.hasResult()) {
                return handleTaskResult(message.getResult()).replaceWith(EngineMessage.getDefaultInstance());
            }
            if (message.hasAck()) {
                LOG.trace("Task acknowledged: {}", message.getAck().getTaskId());
                return acknowledgeTask(message.getAck().getTaskId())
                        .replaceWith(EngineMessage.getDefaultInstance());
            }
            return Uni.createFrom().item(EngineMessage.getDefaultInstance());
        });
    }

    private CommunicationType mapCommunicationType(RegisterExecutorRequest request) {
        CommunicationType communicationType = CommunicationTypeConverter.fromGrpc(request.getCommunicationType());
        return communicationType == CommunicationType.UNSPECIFIED
                ? CommunicationType.GRPC
                : communicationType;
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
                        "Processed result for task: {}, acceptance={}, duplicate={}, runUpdated={}",
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
