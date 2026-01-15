package tech.kayys.silat.sdk.executor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.grpc.ConnectivityState;
import io.grpc.ManagedChannel;
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

import tech.kayys.silat.execution.NodeExecutionResult;
import tech.kayys.silat.execution.NodeExecutionTask;
import tech.kayys.silat.grpc.GrpcMapper;
import tech.kayys.silat.grpc.v1.MutinyExecutorServiceGrpc;
import tech.kayys.silat.grpc.v1.RegisterExecutorRequest;
import tech.kayys.silat.grpc.v1.UnregisterExecutorRequest;
import tech.kayys.silat.grpc.v1.HeartbeatRequest;
import tech.kayys.silat.grpc.v1.StreamTasksRequest;
import tech.kayys.silat.grpc.v1.TaskResult;
import tech.kayys.silat.model.ExecutionToken;
import tech.kayys.silat.model.NodeId;
import tech.kayys.silat.model.WorkflowRunId;

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
    private final Executor executor = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService scheduledExecutor = Executors.newScheduledThreadPool(2);

    public GrpcExecutorTransport() {
        this.executorId = UUID.randomUUID().toString();
    }

    @PostConstruct
    public void init() {
        initializeChannel();
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
            }
        } else {
            channelBuilder.usePlaintext();
        }

        if (jwtEnabled && jwtToken.isPresent()) {
            LOG.info("Configuring JWT interceptor for gRPC channel");
            // Note: JwtClientInterceptor needs to be available in classpath if used
            // channelBuilder.intercept(new JwtClientInterceptor(jwtToken.get()));
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
                startTaskStream();
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
    public tech.kayys.silat.model.CommunicationType getCommunicationType() {
        return tech.kayys.silat.model.CommunicationType.GRPC;
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
                .setCommunicationType(tech.kayys.silat.grpc.v1.CommunicationType.COMMUNICATION_TYPE_GRPC)
                .setEndpoint(java.net.InetAddress.getLoopbackAddress().getHostAddress())
                .setMaxConcurrentTasks(first.getMaxConcurrentTasks())
                .addAllSupportedNodeTypes(java.util.Arrays.asList(first.getSupportedNodeTypes()))
                .build();

        LOG.info("Registering executor {} via gRPC", executorId);

        return stub.registerExecutor(request)
                .onItem().invoke(resp -> LOG.info("Executor registered successfully with ID: {}", resp.getExecutorId()))
                .onFailure().invoke(error -> LOG.error("Failed to register executor {}", executorId, error))
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
                .onFailure().invoke(error -> LOG.error("Failed to unregister executor {}", executorId, error))
                .replaceWithVoid();
    }

    @Override
    public Multi<NodeExecutionTask> receiveTasks() {
        LOG.info("Setting up gRPC task stream for executor: {}", executorId);

        StreamTasksRequest request = StreamTasksRequest.newBuilder()
                .setExecutorId(executorId)
                .build();

        return stub.streamTasks(request)
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
                });
    }

    private void startTaskStream() {
        if (isShutdown.get()) {
            return;
        }
        LOG.info("Task stream setup initiated for executor: {}", executorId);
    }

    @Override
    public Uni<Void> sendResult(NodeExecutionResult result) {
        TaskResult protoResult = TaskResult.newBuilder()
                .setTaskId(result.getNodeId())
                .setRunId(result.runId().value())
                .setNodeId(result.getNodeId())
                .setAttempt(result.attempt())
                .setExecutionToken(result.executionToken().token())
                .setStatus(tech.kayys.silat.grpc.v1.TaskStatus.valueOf("TASK_STATUS_" + result.status().name()))
                .setOutput(mapper.mapToStruct(result.getUpdatedContext().getVariables()))
                .build();

        return stub.reportResults(Multi.createFrom().item(protoResult))
                .onItem().invoke(() -> LOG.debug("Result sent successfully for task: {}", result.getNodeId()))
                .onFailure().invoke(error -> LOG.error("Failed to send result for task: {}", result.getNodeId(), error))
                .replaceWithVoid();
    }

    @Override
    public Uni<Void> sendHeartbeat() {
        if (!isConnected.get()) {
            return Uni.createFrom().voidItem();
        }

        HeartbeatRequest request = HeartbeatRequest.newBuilder()
                .setExecutorId(executorId)
                .build();

        return stub.heartbeat(request)
                .onFailure().invoke(error -> LOG.warn("Heartbeat failed for executor: {}", executorId, error))
                .replaceWithVoid();
    }

    @PreDestroy
    public void cleanup() {
        LOG.info("Cleaning up gRPC transport for executor: {}", executorId);

        isShutdown.set(true);

        if (channel != null && !channel.isShutdown()) {
            try {
                channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                channel.shutdownNow();
            }
        }
    }
}
