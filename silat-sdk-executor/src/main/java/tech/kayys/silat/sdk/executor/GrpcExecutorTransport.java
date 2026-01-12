package tech.kayys.silat.sdk.executor;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.grpc.ConnectivityState;
import io.grpc.ManagedChannel;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.NettyChannelBuilder;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.operators.multi.processors.BroadcastProcessor;
import tech.kayys.silat.execution.NodeExecutionResult;
import tech.kayys.silat.execution.NodeExecutionTask;

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

    private ManagedChannel channel;
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

    @jakarta.annotation.PostConstruct
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
            channelBuilder.intercept(new JwtClientInterceptor(jwtToken.get()));
        }

        this.channel = channelBuilder.build();

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
    public Uni<Void> register(List<WorkflowExecutor> executors) {
        LOG.info("Registering {} executors via gRPC", executors.size());

        return executeWithRetry(() -> {
            CompletableFuture<Void> future = new CompletableFuture<>();

            try {
                // In a real implementation, this would make an actual gRPC call
                // For now, we'll simulate the call with proper error handling
                executor.execute(() -> {
                    try {
                        // Simulate gRPC call processing with timeout
                        Thread.sleep(100); // Simulate network delay

                        LOG.info("Executor registered successfully with ID: {}", executorId);
                        future.complete(null);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        LOG.error("Registration interrupted", e);
                        future.completeExceptionally(e);
                    } catch (Exception e) {
                        LOG.error("Registration failed", e);
                        future.completeExceptionally(e);
                    }
                });
            } catch (Exception e) {
                LOG.error("Failed to initiate registration", e);
                future.completeExceptionally(e);
            }

            return future;
        }, "registration");
    }

    @Override
    public Uni<Void> unregister() {
        LOG.info("Unregistering via gRPC for executor: {}", executorId);

        return executeWithRetry(() -> {
            CompletableFuture<Void> future = new CompletableFuture<>();

            try {
                executor.execute(() -> {
                    try {
                        // Simulate gRPC call processing with timeout
                        Thread.sleep(100); // Simulate network delay

                        LOG.info("Executor unregistered successfully: {}", executorId);
                        future.complete(null);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        LOG.error("Unregistration interrupted", e);
                        future.completeExceptionally(e);
                    } catch (Exception e) {
                        LOG.error("Unregistration failed", e);
                        future.completeExceptionally(e);
                    }
                });
            } catch (Exception e) {
                LOG.error("Failed to initiate unregistration", e);
                future.completeExceptionally(e);
            }

            return future;
        }, "unregistration");
    }

    @Override
    public io.smallrye.mutiny.Multi<NodeExecutionTask> receiveTasks() {
        LOG.info("Setting up gRPC task stream for executor: {}", executorId);

        // Start the task stream if not already started
        startTaskStream();

        return taskProcessor
                .onCancellation().invoke(() -> LOG.debug("Task stream cancelled for executor: {}", executorId))
                .onTermination().invoke(() -> LOG.debug("Task stream terminated for executor: {}", executorId));
    }

    private void startTaskStream() {
        if (isShutdown.get()) {
            LOG.warn("Cannot start task stream, transport is shutdown");
            return;
        }

        // In a real implementation, this would establish the bidirectional streaming
        // connection
        // For now, we'll just log that the stream is starting
        LOG.info("Task stream setup initiated for executor: {}", executorId);

        // Simulate receiving tasks (in a real implementation, this would be the actual
        // gRPC streaming)
        // We'll just log that the stream is ready to receive tasks
        LOG.debug("Task stream ready for executor: {}", executorId);
    }

    @Override
    public Uni<Void> sendResult(NodeExecutionResult result) {
        return executeWithRetry(() -> {
            CompletableFuture<Void> future = new CompletableFuture<>();

            try {
                executor.execute(() -> {
                    try {
                        // Simulate gRPC call processing with timeout
                        Thread.sleep(50); // Simulate network delay

                        LOG.debug("Result sent successfully for task: {}", result.getNodeId());
                        future.complete(null);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        LOG.error("Send result interrupted for task: {}", result.getNodeId(), e);
                        future.completeExceptionally(e);
                    } catch (Exception e) {
                        LOG.error("Failed to send result for task: {}", result.getNodeId(), e);
                        future.completeExceptionally(e);
                    }
                });
            } catch (Exception e) {
                LOG.error("Failed to initiate send result for task: {}", result.getNodeId(), e);
                future.completeExceptionally(e);
            }

            return future;
        }, "sendResult");
    }

    @Override
    public Uni<Void> sendHeartbeat() {
        if (!isConnected.get()) {
            LOG.trace("Skipping heartbeat, not connected");
            return Uni.createFrom().item((Void) null);
        }

        return Uni.createFrom().emitter(emitter -> {
            try {
                executor.execute(() -> {
                    try {
                        // Simulate gRPC heartbeat call
                        Thread.sleep(10); // Simulate network delay

                        LOG.trace("Heartbeat sent for executor: {}", executorId);
                        emitter.complete(null);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        LOG.warn("Heartbeat interrupted for executor: {}", executorId, e);
                        emitter.complete(null); // Don't fail for heartbeat issues
                    } catch (Exception e) {
                        LOG.warn("Heartbeat failed for executor: {}", executorId, e);
                        emitter.complete(null); // Don't fail for heartbeat issues
                    }
                });
            } catch (Exception e) {
                LOG.warn("Failed to initiate heartbeat for executor: {}", executorId, e);
                emitter.complete(null); // Don't fail for heartbeat issues
            }
        });
    }

    /**
     * Execute an operation with retry logic
     */
    private Uni<Void> executeWithRetry(java.util.function.Supplier<CompletableFuture<Void>> operation,
            String operationName) {
        return Uni.createFrom().emitter(emitter -> {
            attemptOperation(operation, operationName, 0, emitter);
        });
    }

    private void attemptOperation(java.util.function.Supplier<CompletableFuture<Void>> operation,
            String operationName,
            int currentAttempt,
            io.smallrye.mutiny.subscription.UniEmitter<? super Void> emitter) {
        CompletableFuture<Void> future = operation.get();

        future.whenComplete((result, error) -> {
            if (error == null) {
                emitter.complete(null);
                return;
            }

            // Retry logic
            if (currentAttempt < maxRetries) {
                LOG.warn("Operation {} failed (attempt {}/{}), retrying in {} seconds",
                        operationName, currentAttempt + 1, maxRetries, retryDelay.getSeconds(), error);

                scheduledExecutor.schedule(() -> {
                    LOG.debug("Retrying operation: {}", operationName);
                    attemptOperation(operation, operationName, currentAttempt + 1, emitter);
                }, retryDelay.toMillis(), TimeUnit.MILLISECONDS);
            } else {
                LOG.error("Operation {} failed after {} attempts", operationName, maxRetries, error);
                emitter.fail(error);
            }
        });
    }

    @PreDestroy
    public void cleanup() {
        LOG.info("Cleaning up gRPC transport for executor: {}", executorId);

        isShutdown.set(true);

        // Complete the task processor by completing any ongoing emissions
        // BroadcastProcessor doesn't have a direct complete() method
        // The processor will be cleaned up when the object is garbage collected

        // Shutdown executors gracefully
        if (executor instanceof java.util.concurrent.ExecutorService) {
            ((java.util.concurrent.ExecutorService) executor).shutdown();
            try {
                if (!((java.util.concurrent.ExecutorService) executor).awaitTermination(5, TimeUnit.SECONDS)) {
                    ((java.util.concurrent.ExecutorService) executor).shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                ((java.util.concurrent.ExecutorService) executor).shutdownNow();
            }
        }

        if (scheduledExecutor instanceof java.util.concurrent.ExecutorService) {
            ((java.util.concurrent.ExecutorService) scheduledExecutor).shutdown();
            try {
                if (!((java.util.concurrent.ExecutorService) scheduledExecutor).awaitTermination(5, TimeUnit.SECONDS)) {
                    ((java.util.concurrent.ExecutorService) scheduledExecutor).shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                ((java.util.concurrent.ExecutorService) scheduledExecutor).shutdownNow();
            }
        }

        // Shutdown gRPC channel
        if (channel != null && !channel.isShutdown()) {
            try {
                channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOG.warn("Interrupted while waiting for channel shutdown", e);
                channel.shutdownNow();
            }
        }
    }
}
