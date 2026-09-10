package tech.kayys.gamelan.dispatcher;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import io.smallrye.mutiny.subscription.UniEmitter;
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

    @Inject
    ObjectMapper objectMapper;

    private static final ObjectMapper DEFAULT_OBJECT_MAPPER = new ObjectMapper();

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
            incrementFailureCounter();
            return Uni.createFrom().failure(
                    new GamelanException(
                            ErrorCode.DISPATCHER_INVALID_REQUEST,
                            "Executor gRPC endpoint is missing"));
        }

        return Uni.createFrom().item(() -> buildRequest(task, executor))
                .onFailure().invoke(t -> {
                    incrementFailureCounter();
                    LOG.error("Failed to build gRPC dispatch request: run={}, node={}, executor={}",
                            task.runId().value(),
                            task.nodeId().value(),
                            executor.executorId(),
                            t);
                })
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
            stub.execute(request, ackObserver(emitter));
        });
    }

    static StreamObserver<ExecutionAck> ackObserver(UniEmitter<? super Void> emitter) {
        return new StreamObserver<>() {
            private boolean accepted;
            private boolean terminal;

            @Override
            public void onNext(ExecutionAck ack) {
                if (terminal) {
                    return;
                }
                if (ack == null || !ack.getAccepted()) {
                    terminal = true;
                    emitter.fail(new TaskDispatchException(
                            "Executor rejected task",
                            ack != null ? ack.getCode() : 502,
                            ack != null ? ack.getMessage() : "missing execution ack"));
                    return;
                }
                accepted = true;
            }

            @Override
            public void onError(Throwable t) {
                if (!terminal) {
                    terminal = true;
                    emitter.fail(t);
                }
            }

            @Override
            public void onCompleted() {
                if (terminal) {
                    return;
                }
                terminal = true;
                if (accepted) {
                    emitter.complete(null);
                    return;
                }
                emitter.fail(new TaskDispatchException(
                        "Executor completed gRPC dispatch without accepting task",
                        502,
                        "missing execution ack"));
            }
        };
    }

    ExecutionRequest buildRequest(NodeExecutionTask task, ExecutorInfo executor) {

        return ExecutionRequest.newBuilder()
                .setRunId(task.runId().value())
                .setNodeId(task.nodeId().value())
                .setAttempt(task.attempt())
                .setToken(task.token().token())
                .putAllVariables(convertVariables(task.context()))
                .setIdempotencyKey(task.idempotencyKey())
                .setSignature(sign(task, executor))
                .build();
    }

    Map<String, String> convertVariables(Map<String, Object> vars) {
        if (vars == null || vars.isEmpty()) {
            return Map.of();
        }

        Map<String, String> result = new HashMap<>();
        vars.forEach((k, v) -> result.put(k, encodeVariable(k, v)));
        return result;
    }

    private void incrementFailureCounter() {
        if (failureCounter != null) {
            failureCounter.increment();
        }
    }

    private String encodeVariable(String key, Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof CharSequence || value instanceof Number || value instanceof Boolean
                || value instanceof Enum<?>) {
            return String.valueOf(value);
        }

        try {
            return mapper().writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new GamelanException(
                    ErrorCode.DISPATCHER_INVALID_REQUEST,
                    "Task context key '" + key + "' cannot be serialized for gRPC dispatch",
                    e);
        }
    }

    private ObjectMapper mapper() {
        return objectMapper != null ? objectMapper : DEFAULT_OBJECT_MAPPER;
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
        // Check gRPC factory availability and attempt health check on first known executor
        if (grpcClientFactory == null) {
            return Uni.createFrom().item(false);
        }

        // For a more thorough check we could ping a known executor,
        // but checking factory + metrics initialization is a good baseline
        boolean healthy = grpcClientFactory != null && meterRegistry != null
                && successCounter != null && failureCounter != null;
        return Uni.createFrom().item(healthy);
    }

    @Override
    public int getPriority() {
        // gRPC is efficient for remote calls, high priority
        return 8;
    }
}
