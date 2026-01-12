package tech.kayys.silat.grpc;

import io.quarkus.grpc.GrpcService;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tech.kayys.silat.engine.WorkflowRunManager;
import tech.kayys.silat.registry.ExecutorRegistry;
import tech.kayys.silat.grpc.v1.*;
import tech.kayys.silat.model.ExecutorInfo;
import tech.kayys.silat.model.WorkflowRunId;

import com.google.protobuf.Empty;
import java.time.Instant;
import java.time.Duration;

/**
 * gRPC service for executor communication
 */
@GrpcService
public class ExecutorServiceImpl implements tech.kayys.silat.grpc.v1.ExecutorService {

    private static final Logger LOG = LoggerFactory.getLogger(ExecutorServiceImpl.class);

    @Inject
    ExecutorRegistry executorRegistry;

    @Inject
    WorkflowRunManager runManager;

    @Inject
    GrpcMapper mapper;

    // ==================== REGISTER EXECUTOR ====================

    // NOTE: If using strict gRPC, method names must match proto service.
    // Quarkus gRPC detects methods by name matching proto service methods.

    public Uni<ExecutorRegistration> registerExecutor(
            RegisterExecutorRequest request) {

        LOG.info("gRPC: Registering executor: {}", request.getExecutorId());

        ExecutorInfo executor = new ExecutorInfo(
                request.getExecutorId(),
                request.getExecutorType(),
                tech.kayys.silat.model.CommunicationType.GRPC,
                request.getEndpoint(),
                Duration.ofSeconds(30), // Default heartbeat interval
                request.getMetadataMap());

        executorRegistry.registerExecutor(executor);

        return Uni.createFrom().item(ExecutorRegistration.newBuilder()
                .setExecutorId(request.getExecutorId())
                .setStatus("REGISTERED")
                .setRegisteredAt(mapper.toProtoTimestamp(Instant.now()))
                .build());
    }

    // ==================== UNREGISTER EXECUTOR ====================

    public Uni<Empty> unregisterExecutor(
            UnregisterExecutorRequest request) {

        LOG.info("gRPC: Unregistering executor: {}", request.getExecutorId());
        executorRegistry.unregisterExecutor(request.getExecutorId());
        return Uni.createFrom().item(Empty.getDefaultInstance());
    }

    // ==================== HEARTBEAT ====================

    public Uni<Empty> heartbeat(HeartbeatRequest request) {
        // LOG.debug("gRPC: Heartbeat from: {}", request.getExecutorId());
        executorRegistry.heartbeat(request.getExecutorId());
        return Uni.createFrom().item(Empty.getDefaultInstance());
    }

    // ==================== STREAM TASKS (SERVER STREAMING) ====================

    @Override
    public Multi<ExecutionTask> streamTasks(StreamTasksRequest request) {

        LOG.info("gRPC: Starting task stream for executor: {}",
                request.getExecutorId());

        // This would pull tasks from the scheduler
        // For now, return empty stream

        return Multi.createFrom().empty();
    }

    // ==================== REPORT RESULTS (CLIENT STREAMING) ====================

    @Override
    public Uni<Empty> reportResults(Multi<TaskResult> results) {

        LOG.info("gRPC: Receiving task results stream");

        return results
                .onItem().invoke(result -> {
                    LOG.debug("Received result for task: {}", result.getTaskId());

                    // Convert to domain object and handle
                    tech.kayys.silat.execution.NodeExecutionResult domainResult = mapper.toDomainNodeResult(result);

                    // Submit to run manager
                    runManager.handleNodeResult(
                            WorkflowRunId.of(result.getRunId()),
                            domainResult).subscribe().with(
                                    v -> LOG.debug("Result processed: {}", result.getTaskId()),
                                    error -> LOG.error("Failed to process result", error));
                })
                .collect().asList()
                .map(list -> Empty.getDefaultInstance());
    }

    // ==================== EXECUTE STREAM (BIDIRECTIONAL) ====================

    @Override
    public Multi<EngineMessage> executeStream(Multi<ExecutorMessage> request) {

        LOG.info("gRPC: Starting bidirectional stream");

        return request.onItem().transform(message -> {
            if (message.hasHeartbeat()) {
                LOG.trace("Heartbeat from executor: {}", message.getHeartbeat().getExecutorId());
            } else if (message.hasResult()) {
                LOG.debug("Result from executor: {}", message.getResult().getTaskId());
            } else if (message.hasAck()) {
                LOG.trace("Task acknowledged: {}", message.getAck().getTaskId());
            }
            // Returing empty/default message for now to satisfy signature
            return EngineMessage.getDefaultInstance();
        });
    }
}
