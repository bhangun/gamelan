package tech.kayys.gamelan.scheduler.redis;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.stream.ClaimedMessages;
import io.quarkus.redis.datasource.stream.XGroupCreateArgs;
import io.quarkus.redis.datasource.stream.XReadGroupArgs;
import io.quarkus.redis.datasource.stream.StreamMessage;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.redis.client.RedisAPI;
import io.vertx.mutiny.redis.client.Response;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import tech.kayys.gamelan.engine.node.NodeExecutionTask;
import tech.kayys.gamelan.scheduler.TaskQueue;
import tech.kayys.gamelan.scheduler.TaskQueueMetadata;

/**
 * High-performance Task Queue using Redis Streams
 */
@ApplicationScoped
@jakarta.enterprise.inject.Alternative
@jakarta.annotation.Priority(1)
public class RedisTaskQueue implements TaskQueue {

    private static final Logger LOG = LoggerFactory.getLogger(RedisTaskQueue.class);
    private static final String PAYLOAD_FIELD = "payload";
    private static final Duration DEFAULT_BLOCK_TIMEOUT = Duration.ofSeconds(1);
    private static final Duration DEFAULT_RECLAIM_IDLE_TIMEOUT = Duration.ofMinutes(5);
    private static final int DEFAULT_READ_BATCH_SIZE = 10;
    private static final int DEFAULT_RECLAIM_BATCH_SIZE = 100;

    public static final String STREAM_KEY = "workflow:tasks:stream";
    public static final String GROUP_NAME = "gamelan-engine-group";
    private static final String CONSUMER_NAME = "engine-" + java.util.UUID.randomUUID().toString().substring(0, 8);

    @Inject
    ReactiveRedisDataSource redis;

    @Inject
    RedisAPI redisApi;

    @ConfigProperty(name = "gamelan.task-queue.redis.block-timeout", defaultValue = "1s")
    Duration blockTimeout;

    @ConfigProperty(name = "gamelan.task-queue.redis.read-batch-size", defaultValue = "10")
    int readBatchSize;

    @ConfigProperty(name = "gamelan.task-queue.redis.reclaim-idle-timeout", defaultValue = "5m")
    Duration reclaimIdleTimeout;

    @ConfigProperty(name = "gamelan.task-queue.redis.reclaim-batch-size", defaultValue = "100")
    int reclaimBatchSize;

    @PostConstruct
    void init() {
        redis.stream(String.class, String.class, NodeExecutionTask.class)
            .xgroupCreate(STREAM_KEY, GROUP_NAME, "0", new XGroupCreateArgs().mkstream())
            .onFailure().recoverWithUni(err -> {
                if (err.getMessage().contains("BUSYGROUP")) {
                    return Uni.createFrom().voidItem();
                }
                LOG.error("Failed to create Redis Stream consumer group", err);
                return Uni.createFrom().failure(err);
            })
            .subscribe().with(v -> LOG.info("Redis Task Queue initialized: Group={}, Consumer={}", GROUP_NAME, CONSUMER_NAME));
    }

    @Override
    public Uni<Void> enqueue(NodeExecutionTask task) {
        NodeExecutionTask deliveryTask = deliveryPayload(task, Instant.now());
        return redis.stream(String.class, String.class, NodeExecutionTask.class)
            .xadd(STREAM_KEY, Map.of(PAYLOAD_FIELD, deliveryTask))
            .invoke(id -> LOG.debug("Task enqueued to Redis Stream: {} (ID: {})", deliveryTask.nodeId().value(), id))
            .replaceWithVoid();
    }

    @Override
    public Multi<QueuedTask> consume() {
        return Multi.createBy().repeating()
            .uni(this::poll)
            .whilst(tasks -> true)
            .onItem().disjoint()
            .map(RedisTaskQueue::castDelivery)
            .map(delivery -> queuedTask(
                    delivery.message().id(),
                    delivery.message().payload().get(PAYLOAD_FIELD),
                    Instant.now(),
                    delivery.reclaimed(),
                    safeDuration(reclaimIdleTimeout, DEFAULT_RECLAIM_IDLE_TIMEOUT)));
    }

    @Override
    public Uni<Void> acknowledge(String messageId) {
        return redis.stream(String.class, String.class, NodeExecutionTask.class)
            .xack(STREAM_KEY, GROUP_NAME, messageId)
            .replaceWithVoid();
    }

    @Override
    public Uni<Boolean> renewLease(QueuedTask queuedTask, Duration leaseDuration) {
        Objects.requireNonNull(queuedTask, "queuedTask cannot be null");
        return pendingEntryOwnedByConsumer(queuedTask.messageId())
                .flatMap(owned -> {
                    if (!owned) {
                        return Uni.createFrom().item(false);
                    }
                    return redisApi.xclaim(renewLeaseCommand(queuedTask.messageId()))
                            .map(RedisTaskQueue::responseHasEntries);
                })
                .onFailure().recoverWithItem(error -> {
                    LOG.warn("Redis task queue lease renewal failed for message {}: {}",
                            queuedTask.messageId(),
                            errorSummary(error));
                    LOG.debug("Redis task queue lease renewal failed", error);
                    return false;
                });
    }

    @Override
    public Uni<TaskQueue.QueueStats> stats() {
        Instant observedAt = Instant.now();
        return redisApi.xinfo(List.of("GROUPS", STREAM_KEY))
                .map(groups -> statsFromGroups(groups, observedAt))
                .onFailure().recoverWithItem(error -> {
                    LOG.warn("Redis task queue stats unavailable: {}", errorSummary(error));
                    LOG.debug("Redis task queue stats unavailable", error);
                    return TaskQueue.QueueStats.unknownAt(observedAt);
                });
    }

    static TaskQueue.QueueStats statsFromGroupValues(
            String groupName,
            Long pending,
            Long lag,
            Instant observedAt) {
        if (!GROUP_NAME.equals(groupName) || pending == null || lag == null) {
            return TaskQueue.QueueStats.unknownAt(observedAt);
        }
        long leased = Math.max(0, pending);
        long available = Math.max(0, lag);
        return TaskQueue.QueueStats.knownAt(
                observedAt,
                available + leased,
                available,
                leased,
                0,
                0);
    }

    static NodeExecutionTask deliveryPayload(NodeExecutionTask task, Instant now) {
        Objects.requireNonNull(task, "task cannot be null");
        return TaskQueueMetadata.deliveryTask(task, now);
    }

    static TaskQueue.QueuedTask queuedTask(String messageId, NodeExecutionTask task, Instant now) {
        return queuedTask(messageId, task, now, false);
    }

    static TaskQueue.QueuedTask queuedTask(String messageId, NodeExecutionTask task, Instant now, boolean reclaimed) {
        return queuedTask(messageId, task, now, reclaimed, DEFAULT_RECLAIM_IDLE_TIMEOUT);
    }

    static TaskQueue.QueuedTask queuedTask(
            String messageId,
            NodeExecutionTask task,
            Instant now,
            boolean reclaimed,
            Duration leaseDuration) {
        Instant reference = now != null ? now : Instant.now();
        Duration effectiveLeaseDuration = safeDuration(leaseDuration, DEFAULT_RECLAIM_IDLE_TIMEOUT);
        TaskQueue.QueuedTask queuedTask = new QueuedTask(
                messageId,
                deliveryPayload(task, reference),
                messageId,
                reference.plus(effectiveLeaseDuration));
        if (!reclaimed) {
            return queuedTask;
        }
        return new QueuedTask(
                queuedTask.messageId(),
                TaskQueueMetadata.redeliveredTask(queuedTask),
                queuedTask.leaseId(),
                queuedTask.leaseExpiresAt());
    }

    private Uni<List<RedisDelivery>> poll() {
        return reclaimStalePending()
                .flatMap(reclaimed -> {
                    if (reclaimed != null && !reclaimed.isEmpty()) {
                        return Uni.createFrom().item(reclaimed);
                    }
                    return readNew();
                });
    }

    private Uni<List<RedisDelivery>> reclaimStalePending() {
        int batchSize = effectiveReclaimBatchSize(readBatchSize, reclaimBatchSize);
        return redis.stream(String.class, String.class, NodeExecutionTask.class)
                .xautoclaim(
                        STREAM_KEY,
                        GROUP_NAME,
                        CONSUMER_NAME,
                        safeDuration(reclaimIdleTimeout, DEFAULT_RECLAIM_IDLE_TIMEOUT),
                        "0-0",
                        batchSize)
                .map(ClaimedMessages::getMessages)
                .onItem().ifNull()
                .continueWith(List.<StreamMessage<String, String, NodeExecutionTask>>of())
                .map(messages -> deliveries(messages, true))
                .onFailure().recoverWithItem(error -> {
                    LOG.warn("Redis task queue stale pending reclaim failed; reading new work only: {}",
                            errorSummary(error));
                    LOG.debug("Redis task queue stale pending reclaim failed", error);
                    return List.of();
                });
    }

    private Uni<List<RedisDelivery>> readNew() {
        return redis.stream(String.class, String.class, NodeExecutionTask.class)
                .xreadgroup(
                        GROUP_NAME,
                        CONSUMER_NAME,
                        Map.of(STREAM_KEY, ">"),
                        new XReadGroupArgs()
                                .count(effectiveReadBatchSize(readBatchSize))
                                .block(safeDuration(blockTimeout, DEFAULT_BLOCK_TIMEOUT)))
                .onItem().ifNull()
                .continueWith(List.<StreamMessage<String, String, NodeExecutionTask>>of())
                .map(messages -> deliveries(messages, false));
    }

    static int effectiveReadBatchSize(int readBatchSize) {
        return positiveInt(readBatchSize, DEFAULT_READ_BATCH_SIZE);
    }

    static int effectiveReclaimBatchSize(int readBatchSize, int reclaimBatchSize) {
        return Math.min(effectiveReadBatchSize(readBatchSize), positiveInt(reclaimBatchSize, DEFAULT_RECLAIM_BATCH_SIZE));
    }

    static Duration safeDuration(Duration value, Duration fallback) {
        return value != null && !value.isZero() && !value.isNegative()
                ? value
                : fallback;
    }

    static List<String> pendingOwnerCommand(String messageId) {
        String normalizedMessageId = requireMessageId(messageId);
        return List.of(STREAM_KEY, GROUP_NAME, normalizedMessageId, normalizedMessageId, "1", CONSUMER_NAME);
    }

    static List<String> renewLeaseCommand(String messageId) {
        return List.of(STREAM_KEY, GROUP_NAME, CONSUMER_NAME, "0", requireMessageId(messageId));
    }

    private static int positiveInt(int value, int fallback) {
        return value > 0 ? value : fallback;
    }

    private Uni<Boolean> pendingEntryOwnedByConsumer(String messageId) {
        return redisApi.xpending(pendingOwnerCommand(messageId))
                .map(RedisTaskQueue::responseHasEntries);
    }

    private static List<RedisDelivery> deliveries(
            List<StreamMessage<String, String, NodeExecutionTask>> messages,
            boolean reclaimed) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        return messages.stream()
                .map(message -> new RedisDelivery(message, reclaimed))
                .toList();
    }

    private static RedisDelivery castDelivery(Object delivery) {
        return (RedisDelivery) delivery;
    }

    private static TaskQueue.QueueStats statsFromGroups(Response groups, Instant observedAt) {
        if (groups == null) {
            return TaskQueue.QueueStats.unknownAt(observedAt);
        }
        for (Response group : groups) {
            Optional<String> name = stringValue(group, "name");
            if (name.isPresent() && GROUP_NAME.equals(name.get())) {
                return statsFromGroupValues(
                        name.get(),
                        longValue(group, "pending").orElse(null),
                        longValue(group, "lag").orElse(null),
                        observedAt);
            }
        }
        return TaskQueue.QueueStats.unknownAt(observedAt);
    }

    private static Optional<String> stringValue(Response keyValuePairs, String key) {
        return value(keyValuePairs, key).map(Response::toString);
    }

    private static Optional<Long> longValue(Response keyValuePairs, String key) {
        return value(keyValuePairs, key).map(Response::toLong);
    }

    private static Optional<Response> value(Response keyValuePairs, String key) {
        if (keyValuePairs == null) {
            return Optional.empty();
        }
        for (int index = 0; index + 1 < keyValuePairs.size(); index += 2) {
            Response candidateKey = keyValuePairs.get(index);
            if (candidateKey != null && key.equals(candidateKey.toString())) {
                return Optional.ofNullable(keyValuePairs.get(index + 1));
            }
        }
        return Optional.empty();
    }

    private static boolean responseHasEntries(Response response) {
        return response != null && response.size() > 0;
    }

    private static String requireMessageId(String messageId) {
        if (messageId == null || messageId.isBlank()) {
            throw new IllegalArgumentException("messageId cannot be blank");
        }
        return messageId.trim();
    }

    private static String errorSummary(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank()
                ? error.getClass().getSimpleName()
                : error.getClass().getSimpleName() + ": " + message.replaceAll("\\s+", " ").trim();
    }

    private record RedisDelivery(StreamMessage<String, String, NodeExecutionTask> message, boolean reclaimed) {
    }
}
