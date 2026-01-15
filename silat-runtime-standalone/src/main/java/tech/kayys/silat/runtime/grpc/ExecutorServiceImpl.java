package tech.kayys.silat.runtime.grpc;

import com.google.protobuf.Empty;
import io.quarkus.grpc.GrpcService;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tech.kayys.silat.grpc.v1.*;
import tech.kayys.silat.model.CommunicationType;
import tech.kayys.silat.dispatcher.CommunicationTypeConverter;
import tech.kayys.silat.model.ExecutorInfo;
import tech.kayys.silat.registry.ExecutorRegistryService;

import java.time.Duration;
import java.util.Map;

@GrpcService
public class ExecutorServiceImpl extends MutinyExecutorServiceGrpc.ExecutorServiceImplBase {

    private static final Logger LOG = LoggerFactory.getLogger(ExecutorServiceImpl.class);

    @Inject
    ExecutorRegistryService executorRegistry;

    public ExecutorServiceImpl() {
        System.out.println("ExecutorServiceImpl initialized!");
        LOG.info("ExecutorServiceImpl initialized!");
    }

    @Override
    public Uni<ExecutorRegistration> registerExecutor(RegisterExecutorRequest request) {
        System.out.println("ExecutorServiceImpl: registerExecutor called for " + request.getExecutorId());
        LOG.info("Received registration request from executor: {}", request.getExecutorId());

        ExecutorInfo executorInfo = new ExecutorInfo(
                request.getExecutorId(),
                request.getExecutorType(),
                mapCommunicationType(request.getCommunicationType()),
                request.getEndpoint(),
                Duration.ofHours(24), // TODO: map from request if available, or use default
                Map.of() // Metadata
        );

        return executorRegistry.registerExecutor(executorInfo)
                .map(v -> ExecutorRegistration.newBuilder()
                        .setExecutorId(request.getExecutorId())
                        .setRegisteredAt(com.google.protobuf.Timestamp.newBuilder()
                                .setSeconds(System.currentTimeMillis() / 1000)
                                .setNanos((int) ((System.currentTimeMillis() % 1000) * 1000000))
                                .build())
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
        return executorRegistry.heartbeat(request.getExecutorId())
                .map(v -> Empty.getDefaultInstance());
    }

    @Override
    public Multi<ExecutionTask> streamTasks(StreamTasksRequest request) {
        LOG.info("Executor {} requested task stream", request.getExecutorId());

        // For now, return an empty stream that won't cause null pointer exceptions
        // TODO: Implement actual task streaming logic
        return Multi.createFrom().empty();
    }

    @Override
    public Uni<Empty> reportResults(Multi<TaskResult> request) {
        return request.onItem().invoke(result -> {
            LOG.info("Received result for task: {} from executor: {}", result.getTaskId(), "UNKNOWN");
            // TODO: Process result
        }).collect().last().map(v -> Empty.getDefaultInstance());
    }

    @Override
    public Multi<EngineMessage> executeStream(Multi<ExecutorMessage> request) {
        return Multi.createFrom().empty();
    }

    private CommunicationType mapCommunicationType(tech.kayys.silat.grpc.v1.CommunicationType grpcType) {
        return CommunicationTypeConverter.fromGrpc(grpcType);
    }
}
