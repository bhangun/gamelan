package tech.kayys.gamelan.sdk.executor;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
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
import io.smallrye.common.annotation.Identifier;
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
import tech.kayys.gamelan.grpc.v1.ExecutorHealth;
import tech.kayys.gamelan.grpc.v1.RegisterExecutorRequest;
import tech.kayys.gamelan.grpc.v1.UnregisterExecutorRequest;
import tech.kayys.gamelan.sdk.executor.core.WorkflowExecutor;
import tech.kayys.gamelan.grpc.v1.HeartbeatRequest;
import tech.kayys.gamelan.grpc.v1.StreamTasksRequest;
import tech.kayys.gamelan.grpc.v1.TaskAcknowledgement;
import tech.kayys.gamelan.grpc.v1.TaskResult;
import tech.kayys.gamelan.engine.execution.ExecutionToken;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;

/**
 * gRPC-based executor transport
 */
@ApplicationScoped
@Identifier("grpc")
public class GrpcExecutorTransport implements RemoteExecutorTransport {

    private static final Logger LOG = LoggerFactory.getLogger(GrpcExecutorTransport.class);
    static final String METADATA_RUNTIME_ID = "gamelan.executor.runtime-id";
    static final String METADATA_EXECUTOR_TYPE = "gamelan.executor.type";
    static final String METADATA_EXECUTOR_VERSION = "gamelan.executor.version";
    static final String METADATA_EXECUTOR_DESCRIPTION = "gamelan.executor.description";
    static final String METADATA_GRPC_DELIVERY = "gamelan.grpc.delivery";
    static final String METADATA_TASK_DELIVERY = "gamelan.task.delivery";
    static final String STREAM_DELIVERY = "stream";

    private final String runtimeId;

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

    @ConfigProperty(name = "gamelan.grpc.task-stream.ack-renewal.enabled", defaultValue = "true")
    boolean ackRenewalEnabled;

    @ConfigProperty(name = "gamelan.grpc.task-stream.ack-renewal.interval", defaultValue = "1m")
    Duration ackRenewalInterval;

    @ConfigProperty(name = "gamelan.grpc.task-stream.ack-renewal.max-duration", defaultValue = "24h")
    Duration ackRenewalMaxDuration;

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
    private final ConcurrentMap<String, RegisteredGrpcExecutor> registeredExecutors = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ActiveStreamedTask> activeStreamedTasks = new ConcurrentHashMap<>();

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
    private final ConcurrentMap<String, CompletableFuture<Void>> taskStreamingFutures = new ConcurrentHashMap<>();

    public GrpcExecutorTransport() {
        this.runtimeId = UUID.randomUUID().toString();
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
        Duration renewalInterval = safeAckRenewalInterval();
        scheduledExecutor.scheduleAtFixedRate(
                this::renewActiveTaskAcknowledgements,
                renewalInterval.toMillis(),
                renewalInterval.toMillis(),
                TimeUnit.MILLISECONDS);
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

                registeredExecutors.values().forEach(this::startTaskStream);
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
        if (executors == null || executors.isEmpty()) {
            return Uni.createFrom().voidItem();
        }

        List<RegisterExecutorRequest> requests = registrationRequests(executors);
        if (requests.isEmpty()) {
            return Uni.createFrom().voidItem();
        }

        Set<String> requestedExecutorIds = requests.stream()
                .map(RegisterExecutorRequest::getExecutorId)
                .collect(java.util.stream.Collectors.toSet());
        List<Uni<Void>> registrations = requests.stream()
                .map(this::registerSingleExecutor)
                .toList();

        LOG.info("Registering {} gRPC executor identities for runtime {}", requests.size(), runtimeId);

        return unregisterStaleExecutors(requestedExecutorIds)
                .chain(() -> Uni.combine().all().unis(registrations).discardItems());
    }

    @Override
    public Uni<Void> unregister() {
        List<String> executorIds = List.copyOf(registeredExecutors.keySet());
        if (executorIds.isEmpty()) {
            return Uni.createFrom().voidItem();
        }

        LOG.info("Unregistering {} gRPC executor identities for runtime {}", executorIds.size(), runtimeId);
        return unregisterExecutorIds(executorIds);
    }

    private Uni<Void> registerSingleExecutor(RegisterExecutorRequest request) {
        LOG.info("Registering executor {} ({}) via gRPC", request.getExecutorId(), request.getExecutorType());

        return stub.registerExecutor(request)
                .onItem().invoke(resp -> {
                    RegisteredGrpcExecutor registration = new RegisteredGrpcExecutor(
                            request.getExecutorId(),
                            request.getExecutorType(),
                            safeMaxConcurrentTasks(request.getMaxConcurrentTasks()));
                    registeredExecutors.put(request.getExecutorId(), registration);
                    startTaskStream(registration);
                    LOG.info("Executor registered successfully with ID: {}", resp.getExecutorId());
                })
                .onFailure().retry().withBackOff(retryDelay, Duration.ofSeconds(1)).atMost(maxRetries)
                .onFailure()
                .invoke(error -> LOG.error("Failed to register executor {} after {} retries",
                        request.getExecutorId(), maxRetries, error))
                .replaceWithVoid();
    }

    private Uni<Void> unregisterExecutorIds(Collection<String> executorIds) {
        if (executorIds == null || executorIds.isEmpty()) {
            return Uni.createFrom().voidItem();
        }

        List<Uni<Void>> unregisters = executorIds.stream()
                .map(this::unregisterSingleExecutor)
                .toList();
        return Uni.combine().all().unis(unregisters).discardItems();
    }

    private Uni<Void> unregisterSingleExecutor(String executorId) {
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
                .onTermination().invoke(() -> {
                    registeredExecutors.remove(executorId);
                    cancelTaskStream(executorId);
                })
                .replaceWithVoid();
    }

    private Uni<Void> unregisterStaleExecutors(Set<String> requestedExecutorIds) {
        List<String> staleExecutorIds = registeredExecutors.keySet().stream()
                .filter(executorId -> !requestedExecutorIds.contains(executorId))
                .toList();
        return unregisterExecutorIds(staleExecutorIds);
    }

    @Override
    public Multi<NodeExecutionTask> receiveTasks() {
        LOG.info("Returning task stream for gRPC runtime: {}", runtimeId);
        return Multi.createFrom().publisher(taskProcessor);
    }

    private void startTaskStream(RegisteredGrpcExecutor registration) {
        if (isShutdown.get()) {
            LOG.warn("Cannot start task stream, transport is shutdown");
            return;
        }

        String executorId = registration.executorId();
        LOG.info("Starting persistent task stream for executor: {}", executorId);

        CompletableFuture<Void> existing = taskStreamingFutures.get(executorId);
        if (existing != null && !existing.isDone()) {
            return;
        }

        CompletableFuture<Void> future = new CompletableFuture<>();
        taskStreamingFutures.put(executorId, future);

        scheduleTaskStreamWithRetry(registration, 0, future);
    }

    private void scheduleTaskStreamWithRetry(
            RegisteredGrpcExecutor registration,
            int retryCount,
            CompletableFuture<Void> streamingFuture) {
        if (isShutdown.get()) {
            streamingFuture.complete(null);
            return;
        }

        String executorId = registration.executorId();
        LOG.info("Attempting to establish task stream for executor {}, attempt #{}", executorId, retryCount + 1);

        StreamTasksRequest request = StreamTasksRequest.newBuilder()
                .setExecutorId(executorId)
                .setMaxConcurrent(registration.maxConcurrentTasks())
                .build();

        // Create the stream and handle items/errors
        stub.streamTasks(request)
                .onItem().transform(protoTask -> {
                    WorkflowRunId runId = WorkflowRunId.of(protoTask.getRunId());
                    NodeId nodeId = NodeId.of(protoTask.getNodeId());
                    int attempt = protoTask.getAttempt();
                    Map<String, Object> context = new HashMap<>(mapper.structToMap(protoTask.getContext()));
                    if (protoTask.getTenantId() != null && !protoTask.getTenantId().isBlank()) {
                        context.put(NodeExecutionTask.TENANT_ID_KEY, protoTask.getTenantId());
                    }
                    TenantId tenantId = protoTask.getTenantId() != null && !protoTask.getTenantId().isBlank()
                            ? TenantId.of(protoTask.getTenantId())
                            : null;
                    ExecutionToken token = new ExecutionToken(
                            protoTask.getExecutionToken(),
                            runId,
                            tenantId,
                            nodeId,
                            attempt,
                            Instant.now().plus(Duration.ofHours(1)));

                    return new ReceivedTask(
                            protoTask.getTaskId(),
                            new NodeExecutionTask(
                                    runId,
                                    nodeId,
                                    attempt,
                                    token,
                                    context,
                                    null // retryPolicy not provided in proto
                            ));
                })
                .subscribe().with(
                        receivedTask -> {
                            trackStreamedTask(receivedTask.taskId(), executorId, Instant.now());
                            acknowledgeTask(receivedTask.taskId());
                            LOG.debug("Received task {} for execution", receivedTask.task().nodeId().value());
                            taskProcessor.onNext(receivedTask.task());
                        },
                        error -> {
                            LOG.error("Error in task stream for executor {}: {}", executorId, error.getMessage());

                            if (isShutdown.get()) {
                                streamingFuture.complete(null);
                                return;
                            }

                            // Check if it's a retryable error
                            if (isRetryableError(error) && retryCount < maxRetries) {
                                LOG.info("Scheduling task stream retry in {} seconds, attempt {}/{}",
                                        retryDelay.getSeconds(), retryCount + 1, maxRetries);

                                scheduledExecutor.schedule(() -> {
                                    if (!isShutdown.get()
                                            && taskStreamingFutures.get(executorId) == streamingFuture
                                            && registeredExecutors.containsKey(executorId)) {
                                        scheduleTaskStreamWithRetry(registration, retryCount + 1, streamingFuture);
                                    }
                                }, retryDelay.toMillis(), TimeUnit.MILLISECONDS);
                            } else {
                                LOG.error(
                                        "Max retries reached or non-retryable error for task stream, stopping attempts");
                                streamingFuture.completeExceptionally(error);
                            }
                        },
                        () -> {
                            LOG.info("Task stream completed for executor: {}", executorId);
                            if (!isShutdown.get()
                                    && taskStreamingFutures.get(executorId) == streamingFuture
                                    && registeredExecutors.containsKey(executorId)) {
                                LOG.info("Restarting task stream for executor: {}", executorId);
                                scheduleTaskStreamWithRetry(registration, 0, streamingFuture);
                            } else {
                                streamingFuture.complete(null);
                            }
                        });
    }

    private void cancelTaskStream(String executorId) {
        CompletableFuture<Void> future = taskStreamingFutures.remove(executorId);
        if (future != null && !future.isDone()) {
            future.cancel(true);
        }
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

    private void acknowledgeTask(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return;
        }

        TaskAcknowledgement acknowledgement = TaskAcknowledgement.newBuilder()
                .setTaskId(taskId)
                .setAcknowledgedAt(mapper.toProtoTimestamp(Instant.now()))
                .build();

        stub.acknowledgeTask(acknowledgement)
                .onFailure().retry().withBackOff(retryDelay, Duration.ofSeconds(1)).atMost(maxRetries)
                .subscribe().with(
                        ignored -> LOG.trace("Acknowledged gRPC task: {}", taskId),
                        error -> LOG.warn("Failed to acknowledge gRPC task {} after {} retries",
                                taskId, maxRetries, error));
    }

    private void renewActiveTaskAcknowledgements() {
        if (!ackRenewalEnabled || isShutdown.get() || !isConnected.get() || activeStreamedTasks.isEmpty()) {
            return;
        }

        activeTaskIdsForRenewal(Instant.now()).forEach(this::acknowledgeTask);
    }

    void trackStreamedTask(String taskId, Instant receivedAt) {
        trackStreamedTask(taskId, null, receivedAt);
    }

    void trackStreamedTask(String taskId, String executorId, Instant receivedAt) {
        if (taskId == null || taskId.isBlank()) {
            return;
        }
        activeStreamedTasks.putIfAbsent(taskId, new ActiveStreamedTask(
                receivedAt != null ? receivedAt : Instant.now(),
                executorId));
    }

    void completeStreamedTask(String taskId) {
        if (taskId != null && !taskId.isBlank()) {
            activeStreamedTasks.remove(taskId);
        }
    }

    boolean hasActiveStreamedTask(String taskId) {
        return taskId != null && activeStreamedTasks.containsKey(taskId);
    }

    List<String> activeTaskIdsForRenewal(Instant now) {
        if (activeStreamedTasks.isEmpty()) {
            return List.of();
        }

        Instant effectiveNow = now != null ? now : Instant.now();
        Duration maxDuration = safeAckRenewalMaxDuration();
        List<String> taskIds = new ArrayList<>();
        for (Map.Entry<String, ActiveStreamedTask> entry : activeStreamedTasks.entrySet()) {
            ActiveStreamedTask activeTask = entry.getValue();
            Instant receivedAt = activeTask != null ? activeTask.receivedAt() : null;
            if (receivedAt != null && Duration.between(receivedAt, effectiveNow).compareTo(maxDuration) > 0) {
                if (activeStreamedTasks.remove(entry.getKey(), activeTask)) {
                    LOG.warn("Stopped renewing gRPC task ACK after max duration: task={}, maxDuration={}",
                            entry.getKey(), maxDuration);
                }
                continue;
            }
            taskIds.add(entry.getKey());
        }
        return List.copyOf(taskIds);
    }

    @Override
    public Uni<Void> sendResult(NodeExecutionResult result) {
        String taskId = NodeExecutionTask.taskId(result.runId(), result.nodeId(), result.attempt());
        TaskResult.Builder builder = TaskResult.newBuilder()
                .setTaskId(taskId)
                .setRunId(result.runId().value())
                .setNodeId(result.getNodeId())
                .setAttempt(result.attempt())
                .setStatus(tech.kayys.gamelan.grpc.v1.TaskStatus.valueOf("TASK_STATUS_" + result.status().name()));

        if (result.executionToken() != null) {
            builder.setExecutionToken(result.executionToken().token());
        }
        if (result.output() != null && !result.output().isEmpty()) {
            builder.setOutput(mapper.mapToStruct(result.output()));
        } else if (result.getUpdatedContext() != null && result.getUpdatedContext().getVariables() != null) {
            builder.setOutput(mapper.mapToStruct(result.getUpdatedContext().getVariables()));
        }
        if (result.error() != null) {
            builder.setError(mapper.toProtoErrorInfo(result.error()));
        }
        if (result.getExecutedAt() != null) {
            builder.setCompletedAt(mapper.toProtoTimestamp(result.getExecutedAt()));
        }
        String tenantId = tenantId(result);
        if (tenantId != null) {
            builder.setTenantId(tenantId);
        }

        TaskResult protoResult = builder.build();

        return stub.reportResults(Multi.createFrom().item(protoResult))
                .onItem().invoke(() -> LOG.debug("Result sent successfully for task: {}", result.getNodeId()))
                .onFailure().retry().withBackOff(retryDelay, Duration.ofSeconds(1)).atMost(maxRetries)
                .onFailure().invoke(error -> LOG.error("Failed to send result for task {} after {} retries",
                        result.getNodeId(), maxRetries, error))
                .onTermination().invoke(() -> completeStreamedTask(taskId))
                .replaceWithVoid();
    }

    private String tenantId(NodeExecutionResult result) {
        if (result == null) {
            return null;
        }
        if (result.executionToken() != null && result.executionToken().tenantId() != null) {
            return result.executionToken().tenantId().value();
        }
        if (result.getMetadata() == null) {
            return null;
        }
        Object value = result.getMetadata().get(NodeExecutionTask.TENANT_ID_KEY);
        if (value == null) {
            value = result.getMetadata().get("tenantId");
        }
        if (value == null) {
            return null;
        }
        String tenantId = String.valueOf(value);
        return tenantId.isBlank() ? null : tenantId;
    }

    @Override
    public Uni<Void> sendHeartbeat() {
        if (!isConnected.get()) {
            LOG.debug("Skipping heartbeat, not connected");
            return Uni.createFrom().voidItem();
        }

        List<String> executorIds = List.copyOf(registeredExecutors.keySet());
        if (executorIds.isEmpty()) {
            LOG.debug("Skipping heartbeat, no registered gRPC executor identities");
            return Uni.createFrom().voidItem();
        }

        List<Uni<Void>> heartbeats = executorIds.stream()
                .map(this::sendHeartbeat)
                .toList();
        return Uni.combine().all().unis(heartbeats).discardItems();
    }

    private Uni<Void> sendHeartbeat(String executorId) {
        HeartbeatRequest request = heartbeatRequest(executorId);

        return stub.heartbeat(request)
                .onItem().invoke(() -> LOG.trace("Heartbeat sent successfully for executor: {}", executorId))
                .onFailure().invoke(error -> LOG.warn("Heartbeat failed for executor: {}", executorId, error))
                .replaceWithVoid();
    }

    HeartbeatRequest heartbeatRequest(String executorId) {
        int currentTaskCount = activeTaskCount(executorId);
        return HeartbeatRequest.newBuilder()
                .setExecutorId(executorId)
                .setCurrentTaskCount(currentTaskCount)
                .setHealth(ExecutorHealth.newBuilder()
                        .setStatus(heartbeatStatus(executorId, currentTaskCount))
                        .setCurrentTasks(currentTaskCount)
                        .build())
                .build();
    }

    int activeTaskCount(String executorId) {
        if (executorId == null || executorId.isBlank()) {
            return activeStreamedTasks.size();
        }
        int count = 0;
        for (ActiveStreamedTask activeTask : activeStreamedTasks.values()) {
            if (activeTask != null && executorId.equals(activeTask.executorId())) {
                count++;
            }
        }
        return count;
    }

    private String heartbeatStatus(String executorId, int currentTaskCount) {
        RegisteredGrpcExecutor executor = registeredExecutors.get(executorId);
        if (executor == null) {
            return currentTaskCount > 0 ? "BUSY" : "READY";
        }
        return currentTaskCount >= executor.maxConcurrentTasks() ? "SATURATED"
                : currentTaskCount > 0 ? "BUSY" : "READY";
    }

    @PreDestroy
    public void cleanup() {
        LOG.info("Cleaning up gRPC transport for runtime: {}", runtimeId);

        isShutdown.set(true);

        // Cancel task streaming
        taskStreamingFutures.keySet().forEach(this::cancelTaskStream);
        registeredExecutors.clear();
        activeStreamedTasks.clear();

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

    List<RegisterExecutorRequest> registrationRequests(List<WorkflowExecutor> executors) {
        if (executors == null || executors.isEmpty()) {
            return List.of();
        }

        Map<String, WorkflowExecutor> executorsByType = new LinkedHashMap<>();
        for (WorkflowExecutor executor : executors) {
            if (executor == null) {
                continue;
            }
            String executorType = normalizedExecutorType(executor.getExecutorType());
            WorkflowExecutor existing = executorsByType.putIfAbsent(executorType, executor);
            if (existing != null) {
                LOG.warn("Ignoring duplicate gRPC executor type '{}' in runtime {}", executorType, runtimeId);
            }
        }

        List<RegisterExecutorRequest> requests = new ArrayList<>(executorsByType.size());
        for (Map.Entry<String, WorkflowExecutor> entry : executorsByType.entrySet()) {
            requests.add(registrationRequest(entry.getKey(), entry.getValue()));
        }
        return List.copyOf(requests);
    }

    private RegisterExecutorRequest registrationRequest(String executorType, WorkflowExecutor executor) {
        String executorId = executorIdFor(executorType);
        return RegisterExecutorRequest.newBuilder()
                .setExecutorId(executorId)
                .setExecutorType(executorType)
                .setCommunicationType(tech.kayys.gamelan.grpc.v1.CommunicationType.COMMUNICATION_TYPE_GRPC)
                .setEndpoint("")
                .putAllMetadata(registrationMetadata(executorId, executorType, executor))
                .addAllSupportedNodeTypes(supportedNodeTypes(executor))
                .setMaxConcurrentTasks(safeMaxConcurrentTasks(executor.getMaxConcurrentTasks()))
                .build();
    }

    String executorIdFor(String executorType) {
        String normalizedType = normalizedExecutorType(executorType);
        return runtimeId
                + "-"
                + sanitizeExecutorIdSegment(normalizedType)
                + "-"
                + Integer.toUnsignedString(normalizedType.hashCode(), 36);
    }

    private Map<String, String> registrationMetadata(
            String executorId,
            String executorType,
            WorkflowExecutor executor) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put(METADATA_GRPC_DELIVERY, STREAM_DELIVERY);
        metadata.put(METADATA_TASK_DELIVERY, STREAM_DELIVERY);
        metadata.put(METADATA_RUNTIME_ID, runtimeId);
        metadata.put(METADATA_EXECUTOR_TYPE, executorType);
        metadata.put("gamelan.executor.id", executorId);
        putIfPresent(metadata, METADATA_EXECUTOR_VERSION, executor.getVersion());
        putIfPresent(metadata, METADATA_EXECUTOR_DESCRIPTION, executor.getDescription());
        return Map.copyOf(metadata);
    }

    private static List<String> supportedNodeTypes(WorkflowExecutor executor) {
        String[] supportedTypes = executor.getSupportedNodeTypes();
        if (supportedTypes == null || supportedTypes.length == 0) {
            return List.of();
        }

        LinkedHashSet<String> normalizedTypes = new LinkedHashSet<>();
        for (String supportedType : supportedTypes) {
            if (supportedType != null && !supportedType.isBlank()) {
                normalizedTypes.add(supportedType.trim());
            }
        }
        return List.copyOf(normalizedTypes);
    }

    private static int safeMaxConcurrentTasks(int maxConcurrentTasks) {
        return Math.max(1, maxConcurrentTasks);
    }

    private Duration safeAckRenewalInterval() {
        if (ackRenewalInterval == null || ackRenewalInterval.isZero() || ackRenewalInterval.isNegative()) {
            return Duration.ofMinutes(1);
        }
        return ackRenewalInterval;
    }

    private Duration safeAckRenewalMaxDuration() {
        if (ackRenewalMaxDuration == null || ackRenewalMaxDuration.isZero() || ackRenewalMaxDuration.isNegative()) {
            return Duration.ofHours(24);
        }
        return ackRenewalMaxDuration;
    }

    private static String normalizedExecutorType(String executorType) {
        String normalized = Objects.requireNonNullElse(executorType, "").trim();
        return normalized.isEmpty() ? "unspecified" : normalized;
    }

    private static String sanitizeExecutorIdSegment(String value) {
        String sanitized = value.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9._-]+", "-")
                .replaceAll("^-+|-+$", "");
        return sanitized.isBlank() ? "executor" : sanitized;
    }

    private static void putIfPresent(Map<String, String> metadata, String key, String value) {
        if (value != null && !value.isBlank()) {
            metadata.put(key, value.trim());
        }
    }

    private record RegisteredGrpcExecutor(
            String executorId,
            String executorType,
            int maxConcurrentTasks) {
    }

    private record ActiveStreamedTask(Instant receivedAt, String executorId) {
    }

    private record ReceivedTask(String taskId, NodeExecutionTask task) {
    }
}
