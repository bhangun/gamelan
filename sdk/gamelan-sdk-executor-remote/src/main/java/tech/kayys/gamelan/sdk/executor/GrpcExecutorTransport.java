package tech.kayys.gamelan.sdk.executor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.grpc.ConnectivityState;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.NettyChannelBuilder;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.operators.multi.processors.BroadcastProcessor;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import tech.kayys.gamelan.engine.node.NodeExecutionResult;
import tech.kayys.gamelan.engine.node.NodeExecutionTask;
import tech.kayys.gamelan.grpc.GrpcMapper;
import tech.kayys.gamelan.grpc.v1.MutinyExecutorServiceGrpc;
import tech.kayys.gamelan.grpc.v1.RegisterExecutorRequest;
import tech.kayys.gamelan.grpc.v1.UnregisterExecutorRequest;
import tech.kayys.gamelan.sdk.executor.core.ExecutorTransport;
import tech.kayys.gamelan.sdk.executor.core.WorkflowExecutor;
import tech.kayys.gamelan.grpc.v1.HeartbeatRequest;
import tech.kayys.gamelan.grpc.v1.StreamTasksRequest;
import tech.kayys.gamelan.grpc.v1.TaskResult;
import tech.kayys.gamelan.engine.execution.ExecutionToken;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;

/**
 * gRPC-based executor transport
 */
@ApplicationScoped
public class GrpcExecutorTransport implements ExecutorTransport {

    private static final Logger LOG = LoggerFactory.getLogger(GrpcExecutorTransport.class);

    private final String executorId;

    @ConfigProperty(name = "engine.grpc.endpoint", defaultValue = "localhost")
    String engineEndpoint;

    @ConfigProperty(name = "engine.grpc.port", defaultValue = "9090")
    int grpcPort;

    @ConfigProperty(name = "heartbeat.interval", defaultValue = "30s")
    Duration heartbeatInterval;

    @ConfigProperty(name = "grpc.max.retries", defaultValue = "3")
    int maxRetries;

    @ConfigProperty(name = "grpc.retry.delay", defaultValue = "5s")
    Duration retryDelay;

    @ConfigProperty(name = "security.mtls.enabled", defaultValue = "false")
    boolean mtlsEnabled;

    @ConfigProperty(name = "security.jwt.enabled", defaultValue = "false")
    boolean jwtEnabled;

    @ConfigProperty(name = "security.mtls.cert.path")
    java.util.Optional<String> keyCertChainPath;

    @ConfigProperty(name = "security.mtls.key.path")
    java.util.Optional<String> privateKeyPath;

    @ConfigProperty(name = "security.mtls.trust.path")
    java.util.Optional<String> trustCertCollectionPath;

    @ConfigProperty(name = "security.jwt.token")
    java.util.Optional<String> jwtToken;

    @Inject
    GrpcMapper mapper;

    private ManagedChannel channel;
    private MutinyExecutorServiceGrpc.MutinyExecutorServiceStub stub;
    private final AtomicBoolean isConnected = new AtomicBoolean(false);
    private final AtomicBoolean isShutdown = new AtomicBoolean(false);

    // For streaming task reception
    private final BroadcastProcessor<NodeExecutionTask> taskProcessor = BroadcastProcessor.create();

    // For background operations
    private final ScheduledExecutorService scheduledExecutor = Executors.newScheduledThreadPool(2, new ThreadFactory() {
        private final AtomicInteger counter = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable r) {
            Thread thread = new Thread(r, "gamelan-grpc-transport-" + counter.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    });

    // Task streaming state
    private volatile CompletableFuture<Void> taskStreamingFuture;

    public GrpcExecutorTransport() {
        this.executorId = UUID.randomUUID().toString();
    }

    @PostConstruct
    public void init() {
        initializeChannel();
        startTaskStream(); // Start task streaming after initialization
    }

    private void initializeChannel() {
        NettyChannelBuilder channelBuilder = NettyChannelBuilder
                .forAddress(engineEndpoint, grpcPort)
                .keepAliveTime(1, TimeUnit.MINUTES)
                .keepAliveTimeout(20, TimeUnit.SECONDS)
                .keepAliveWithoutCalls(true)
                .maxInboundMessageSize(4 * 1024 * 1024) // 4MB
                .defaultLoadBalancingPolicy("round_robin");

        if (mtlsEnabled) {
            LOG.info("Configuring mTLS for gRPC channel");
            try {
                SslContextBuilder sslContextBuilder = GrpcSslContexts.forClient();
                if (trustCertCollectionPath.isPresent()) {
                    sslContextBuilder.trustManager(new java.io.File(trustCertCollectionPath.get()));
                }
                if (keyCertChainPath.isPresent() && privateKeyPath.isPresent()) {
                    sslContextBuilder.keyManager(
                            new java.io.File(keyCertChainPath.get()),
                            new java.io.File(privateKeyPath.get()));
                }
                SslContext sslContext = sslContextBuilder.build();
                channelBuilder.sslContext(sslContext).useTransportSecurity();
            } catch (Exception e) {
                LOG.error("Failed to configure mTLS", e);
                throw new RuntimeException("Failed to configure mTLS", e);
            }
        } else {
            channelBuilder.usePlaintext();
        }

        if (jwtEnabled && jwtToken.isPresent()) {
            LOG.info("Configuring JWT interceptor for gRPC channel");
            channelBuilder.intercept(new JwtClientInterceptor(jwtToken.get()));
        }

        this.channel = channelBuilder.build();
        this.stub = MutinyExecutorServiceGrpc.newMutinyStub(channel);

        // Monitor connection state
        scheduledExecutor.scheduleAtFixedRate(this::checkConnectionState, 0, 5, TimeUnit.SECONDS);
    }

    private void checkConnectionState() {
        if (isShutdown.get()) {
            return;
        }

        try {
            ConnectivityState state = channel.getState(false);
            boolean wasConnected = isConnected.get();
            boolean nowConnected = state == ConnectivityState.READY || state == ConnectivityState.IDLE;

            if (wasConnected && !nowConnected) {
                LOG.warn("gRPC connection lost, state: {}", state);
                isConnected.set(false);
            } else if (!wasConnected && nowConnected) {
                LOG.info("gRPC connection restored");
                isConnected.set(true);

                // Restart task stream if needed
                if (taskStreamingFuture == null || taskStreamingFuture.isDone()) {
                    startTaskStream();
                }
            }
        } catch (Exception e) {
            LOG.warn("Error checking gRPC connection state", e);
        }
    }

    @Override
    public Duration getHeartbeatInterval() {
        return heartbeatInterval;
    }

    @Override
    public tech.kayys.gamelan.engine.protocol.CommunicationType getCommunicationType() {
        return tech.kayys.gamelan.engine.protocol.CommunicationType.GRPC;
    }

    @Override
    public Uni<Void> register(List<WorkflowExecutor> executors) {
        if (executors.isEmpty()) {
            return Uni.createFrom().voidItem();
        }

        WorkflowExecutor first = executors.get(0);
        RegisterExecutorRequest request = RegisterExecutorRequest.newBuilder()
                .setExecutorId(executorId)
                .setExecutorType(first.getExecutorType())
                .setCommunicationType(tech.kayys.gamelan.grpc.v1.CommunicationType.COMMUNICATION_TYPE_GRPC)
                .setEndpoint(java.net.InetAddress.getLoopbackAddress().getHostAddress())
                .setMaxConcurrentTasks(first.getMaxConcurrentTasks())
                .addAllSupportedNodeTypes(java.util.Arrays.asList(first.getSupportedNodeTypes()))
                .build();

        LOG.info("Registering executor {} via gRPC", executorId);

        return stub.registerExecutor(request)
                .onItem().invoke(resp -> LOG.info("Executor registered successfully with ID: {}", resp.getExecutorId()))
                .onFailure().retry().withBackOff(retryDelay, Duration.ofSeconds(1)).atMost(maxRetries)
                .onFailure()
                .invoke(error -> LOG.error("Failed to register executor {} after {} retries", executorId, maxRetries,
                        error))
                .replaceWithVoid();
    }

    @Override
    public Uni<Void> unregister() {
        UnregisterExecutorRequest request = UnregisterExecutorRequest.newBuilder()
                .setExecutorId(executorId)
                .build();

        LOG.info("Unregistering executor {} via gRPC", executorId);

        return stub.unregisterExecutor(request)
                .onItem().invoke(resp -> LOG.info("Executor unregistered successfully: {}", executorId))
                .onFailure().retry().withBackOff(retryDelay, Duration.ofSeconds(1)).atMost(maxRetries)
                .onFailure()
                .invoke(error -> LOG.error("Failed to unregister executor {} after {} retries", executorId, maxRetries,
                        error))
                .replaceWithVoid();
    }

    @Override
    public Multi<NodeExecutionTask> receiveTasks() {
        LOG.info("Returning task stream for executor: {}", executorId);
        return Multi.createFrom().publisher(taskProcessor);
    }

    private void startTaskStream() {
        if (isShutdown.get()) {
            LOG.warn("Cannot start task stream, transport is shutdown");
            return;
        }

        LOG.info("Starting persistent task stream for executor: {}", executorId);

        // Cancel any existing streaming future
        if (taskStreamingFuture != null && !taskStreamingFuture.isDone()) {
            taskStreamingFuture.cancel(true);
        }

        // Create a new streaming future
        taskStreamingFuture = new CompletableFuture<>();

        // Start the task streaming with retry logic
        scheduleTaskStreamWithRetry(0);
    }

    private void scheduleTaskStreamWithRetry(int retryCount) {
        if (isShutdown.get()) {
            taskStreamingFuture.complete(null);
            return;
        }

        LOG.info("Attempting to establish task stream, attempt #{}", retryCount + 1);

        StreamTasksRequest request = StreamTasksRequest.newBuilder()
                .setExecutorId(executorId)
                .build();

        // Create the stream and handle items/errors
        stub.streamTasks(request)
                .onItem().transform(protoTask -> {
                    WorkflowRunId runId = WorkflowRunId.of(protoTask.getRunId());
                    NodeId nodeId = NodeId.of(protoTask.getNodeId());
                    int attempt = protoTask.getAttempt();
                    ExecutionToken token = new ExecutionToken(
                            protoTask.getExecutionToken(),
                            runId,
                            nodeId,
                            attempt,
                            Instant.now().plus(Duration.ofHours(1)));

                    return new NodeExecutionTask(
                            runId,
                            nodeId,
                            attempt,
                            token,
                            mapper.structToMap(protoTask.getContext()),
                            null // retryPolicy not provided in proto
                    );
                })
                .subscribe().with(
                        task -> {
                            LOG.debug("Received task {} for execution", task.nodeId().value());
                            taskProcessor.onNext(task);
                        },
                        error -> {
                            LOG.error("Error in task stream for executor {}: {}", executorId, error.getMessage());

                            if (isShutdown.get()) {
                                taskStreamingFuture.complete(null);
                                return;
                            }

                            // Check if it's a retryable error
                            if (isRetryableError(error) && retryCount < maxRetries) {
                                LOG.info("Scheduling task stream retry in {} seconds, attempt {}/{}",
                                        retryDelay.getSeconds(), retryCount + 1, maxRetries);

                                scheduledExecutor.schedule(() -> {
                                    if (!isShutdown.get()) {
                                        scheduleTaskStreamWithRetry(retryCount + 1);
                                    }
                                }, retryDelay.toMillis(), TimeUnit.MILLISECONDS);
                            } else {
                                LOG.error(
                                        "Max retries reached or non-retryable error for task stream, stopping attempts");
                                taskStreamingFuture.completeExceptionally(error);
                            }
                        },
                        () -> {
                            LOG.info("Task stream completed for executor: {}", executorId);
                            if (!isShutdown.get()) {
                                LOG.info("Restarting task stream for executor: {}", executorId);
                                scheduleTaskStreamWithRetry(0);
                            } else {
                                taskStreamingFuture.complete(null);
                            }
                        });
    }

    private boolean isRetryableError(Throwable error) {
        if (error instanceof StatusRuntimeException) {
            Status status = ((StatusRuntimeException) error).getStatus();
            return status.getCode() == Status.Code.UNAVAILABLE ||
                    status.getCode() == Status.Code.DEADLINE_EXCEEDED ||
                    status.getCode() == Status.Code.INTERNAL ||
                    status.getCode() == Status.Code.UNKNOWN;
        }
        return true; // Assume other errors are retryable
    }

    @Override
    public Uni<Void> sendResult(NodeExecutionResult result) {
        TaskResult protoResult = TaskResult.newBuilder()
                .setTaskId(result.getNodeId())
                .setRunId(result.runId().value())
                .setNodeId(result.getNodeId())
                .setAttempt(result.attempt())
                .setExecutionToken(result.executionToken().token())
                .setStatus(tech.kayys.gamelan.grpc.v1.TaskStatus.valueOf("TASK_STATUS_" + result.status().name()))
                .setOutput(mapper.mapToStruct(result.getUpdatedContext().getVariables()))
                .build();

        return stub.reportResults(Multi.createFrom().item(protoResult))
                .onItem().invoke(() -> LOG.debug("Result sent successfully for task: {}", result.getNodeId()))
                .onFailure().retry().withBackOff(retryDelay, Duration.ofSeconds(1)).atMost(maxRetries)
                .onFailure().invoke(error -> LOG.error("Failed to send result for task {} after {} retries",
                        result.getNodeId(), maxRetries, error))
                .replaceWithVoid();
    }

    @Override
    public Uni<Void> sendHeartbeat() {
        if (!isConnected.get()) {
            LOG.debug("Skipping heartbeat, not connected");
            return Uni.createFrom().voidItem();
        }

        HeartbeatRequest request = HeartbeatRequest.newBuilder()
                .setExecutorId(executorId)
                .build();

        return stub.heartbeat(request)
                .onItem().invoke(() -> LOG.trace("Heartbeat sent successfully for executor: {}", executorId))
                .onFailure().invoke(error -> LOG.warn("Heartbeat failed for executor: {}", executorId, error))
                .replaceWithVoid();
    }

    @PreDestroy
    public void cleanup() {
        LOG.info("Cleaning up gRPC transport for executor: {}", executorId);

        isShutdown.set(true);

        // Cancel task streaming
        if (taskStreamingFuture != null && !taskStreamingFuture.isDone()) {
            taskStreamingFuture.cancel(true);
        }

        // Shutdown processors
        taskProcessor.onComplete();

        if (channel != null && !channel.isShutdown()) {
            try {
                channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                channel.shutdownNow();
            }
        }

        scheduledExecutor.shutdown();
        try {
            if (!scheduledExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduledExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            scheduledExecutor.shutdownNow();
        }
    }
}
