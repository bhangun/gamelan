package tech.kayys.gamelan.dispatcher;

import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import tech.kayys.gamelan.engine.node.NodeExecutionTask;
import tech.kayys.gamelan.engine.protocol.CommunicationType;
import tech.kayys.gamelan.engine.executor.ExecutorInfo;
import tech.kayys.gamelan.engine.error.ErrorCode;
import tech.kayys.gamelan.engine.error.GamelanException;

@ApplicationScoped
public class GrpcTaskDispatcher implements TaskDispatcher {

    private static final Logger LOG = LoggerFactory.getLogger(GrpcTaskDispatcher.class);

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    @Inject
    GrpcClientFactory grpcClientFactory;

    @Inject
    MeterRegistry meterRegistry;

    private Counter successCounter;
    private Counter failureCounter;
    private Timer dispatchTimer;

    @jakarta.annotation.PostConstruct
    void initMetrics() {
        this.successCounter = Counter.builder("gamelan.dispatcher.grpc.success")
                .description("Number of successful gRPC dispatches")
                .register(meterRegistry);
        this.failureCounter = Counter.builder("gamelan.dispatcher.grpc.failure")
                .description("Number of failed gRPC dispatches")
                .register(meterRegistry);
        this.dispatchTimer = Timer.builder("gamelan.dispatcher.grpc.duration")
                .description("gRPC dispatch duration")
                .register(meterRegistry);
    }

    @Override
    public Uni<Void> dispatch(NodeExecutionTask task, ExecutorInfo executor) {

        Objects.requireNonNull(task, "NodeExecutionTask cannot be null");
        Objects.requireNonNull(executor, "ExecutorInfo cannot be null");

        if (executor.endpoint() == null || executor.endpoint().isBlank()) {
            failureCounter.increment();
            return Uni.createFrom().failure(
                    new GamelanException(
                            ErrorCode.DISPATCHER_INVALID_REQUEST,
                            "Executor gRPC endpoint is missing"));
        }

        return Uni.createFrom().item(buildRequest(task, executor))
                .flatMap(req -> {
                    Timer.Sample sample = Timer.start(meterRegistry);
                    return send(req, executor)
                            .invoke(() -> {
                                sample.stop(dispatchTimer);
                                successCounter.increment();
                            })
                            .onFailure().invoke(t -> {
                                sample.stop(dispatchTimer);
                                failureCounter.increment();
                                LOG.error("gRPC dispatch failed: run={}, node={}, executor={}",
                                        task.runId().value(),
                                        task.nodeId().value(),
                                        executor.executorId(),
                                        t);
                            });
                })
                .replaceWithVoid();
    }

    private Uni<Void> send(ExecutionRequest request, ExecutorInfo executor) {

        ExecutorGrpc.ExecutorStub stub = grpcClientFactory.getStub(executor)
                .withDeadlineAfter(
                        resolveTimeout(executor).toMillis(),
                        TimeUnit.MILLISECONDS);

        return Uni.createFrom().emitter(emitter -> {

            stub.execute(request, new StreamObserver<ExecutionAck>() {

                @Override
                public void onNext(ExecutionAck ack) {
                    if (!ack.getAccepted()) {
                        emitter.fail(new TaskDispatchException(
                                "Executor rejected task",
                                ack.getCode(),
                                ack.getMessage()));
                    }
                }

                @Override
                public void onError(Throwable t) {
                    emitter.fail(t);
                }

                @Override
                public void onCompleted() {
                    emitter.complete(null);
                }
            });
        });
    }

    private ExecutionRequest buildRequest(NodeExecutionTask task, ExecutorInfo executor) {

        return ExecutionRequest.newBuilder()
                .setRunId(task.runId().value())
                .setNodeId(task.nodeId().value())
                .setAttempt(task.attempt())
                .setToken(task.token().token())
                .putAllVariables(convertVariables(task.context()))
                .setIdempotencyKey(idempotencyKey(task))
                .setSignature(sign(task, executor))
                .build();
    }

    private Map<String, String> convertVariables(Map<String, Object> vars) {
        if (vars == null || vars.isEmpty()) {
            return Map.of();
        }

        Map<String, String> result = new HashMap<>();
        vars.forEach((k, v) -> result.put(k, String.valueOf(v)));
        return result;
    }

    private String idempotencyKey(NodeExecutionTask task) {
        return task.runId().value()
                + ":" + task.nodeId().value()
                + ":" + task.attempt();
    }

    private String sign(NodeExecutionTask task, ExecutorInfo executor) {
        // placeholder – replace with HMAC / mTLS / JWT
        return Base64.getEncoder()
                .encodeToString(
                        (task.runId().value()
                                + executor.executorId()).getBytes());
    }

    private Duration resolveTimeout(ExecutorInfo executor) {
        return executor.timeout() != null
                ? executor.timeout()
                : DEFAULT_TIMEOUT;
    }

    @Override
    public boolean supports(ExecutorInfo executor) {
        return executor != null && executor.communicationType() == CommunicationType.GRPC;
    }

    @Override
    public Uni<Boolean> isHealthy() {
        // For gRPC, we could implement a health check call, but for now just check if
        // factory is available
        return Uni.createFrom().item(grpcClientFactory != null);
    }

    @Override
    public int getPriority() {
        // gRPC is efficient for remote calls, high priority
        return 8;
    }
}
