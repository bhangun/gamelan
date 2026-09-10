package tech.kayys.gamelan.dispatcher;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.quarkus.arc.properties.IfBuildProperty;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.sortedset.ScoreRange;
import io.quarkus.redis.datasource.stream.ClaimedMessages;
import io.quarkus.redis.datasource.stream.StreamMessage;
import io.quarkus.redis.datasource.stream.XGroupCreateArgs;
import io.quarkus.redis.datasource.stream.XReadGroupArgs;
import io.quarkus.redis.datasource.value.SetArgs;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import tech.kayys.gamelan.engine.node.NodeExecutionTask;

@ApplicationScoped
@IfBuildProperty(name = "gamelan.grpc.task-stream.broker", stringValue = "redis")
public class RedisGrpcTaskStreamBroker implements GrpcTaskStreamBroker {

    private static final Logger LOG = LoggerFactory.getLogger(RedisGrpcTaskStreamBroker.class);

    private static final String STREAM_PREFIX = "gamelan:grpc-stream:executor:";
    private static final String TASK_OWNER_PREFIX = "gamelan:grpc-stream:task-owner:";
    private static final String TASK_MESSAGE_PREFIX = "gamelan:grpc-stream:task-message:";
    private static final String IN_FLIGHT_PREFIX = "gamelan:grpc-stream:in-flight:";
    private static final String PAYLOAD_FIELD = "payload";
    private static final String GROUP_NAME = "gamelan-grpc-stream";

    private final java.util.Set<String> initializedStreams = java.util.concurrent.ConcurrentHashMap.newKeySet();

    @Inject
    ReactiveRedisDataSource redis;

    @ConfigProperty(name = "gamelan.grpc.task-stream.redis.block-timeout", defaultValue = "1s")
    Duration blockTimeout;

    @ConfigProperty(name = "gamelan.grpc.task-stream.redis.reclaim-idle-timeout", defaultValue = "5m")
    Duration reclaimIdleTimeout;

    @ConfigProperty(name = "gamelan.grpc.task-stream.redis.reclaim-batch-size", defaultValue = "100")
    int reclaimBatchSize;

    @ConfigProperty(name = "gamelan.grpc.task-stream.redis.assignment-claim-ttl", defaultValue = "30s")
    Duration assignmentClaimTtl;

    @Override
    public Uni<Void> assign(String executorId, NodeExecutionTask task) {
        if (executorId == null || executorId.isBlank()) {
            return Uni.createFrom().failure(new IllegalArgumentException("executorId cannot be blank"));
        }
        if (task == null) {
            return Uni.createFrom().failure(new IllegalArgumentException("task cannot be null"));
        }

        String streamKey = streamKey(executorId);
        String taskId = GrpcTaskStreamBroker.taskId(task);
        return ensureGroup(streamKey)
                .chain(() -> claimAssignment(taskId, executorId))
                .flatMap(claimed -> {
                    if (!claimed) {
                        LOG.debug("Skipped duplicate gRPC stream task {} for executor {}; assignment already claimed",
                                taskId, executorId);
                        return Uni.createFrom().voidItem();
                    }
                    return redis.stream(String.class, String.class, NodeExecutionTask.class)
                            .xadd(streamKey, Map.of(PAYLOAD_FIELD, task))
                            .onFailure().call(() -> releaseAssignmentClaim(taskId))
                            .call(messageId -> rememberAssignment(executorId, taskId, messageId))
                            .invoke(messageId -> LOG.debug("Assigned gRPC stream task {} to Redis stream {} as {}",
                                    taskId, streamKey, messageId))
                            .replaceWithVoid();
                });
    }

    @Override
    public Multi<StreamedTask> stream(String executorId, int maxConcurrent) {
        if (executorId == null || executorId.isBlank()) {
            return Multi.createFrom().failure(new IllegalArgumentException("executorId cannot be blank"));
        }

        int capacity = Math.max(1, maxConcurrent);
        String streamKey = streamKey(executorId);
        String consumerName = consumerName(executorId);

        return ensureGroup(streamKey)
                .onItem().transformToMulti(ignored -> Multi.createBy().repeating()
                        .uni(() -> poll(executorId, streamKey, consumerName, capacity))
                        .whilst(messages -> true)
                        .onItem().disjoint()
                        .onItem().transform(this::castMessage)
                        .onItem().transformToUniAndConcatenate(message -> toStreamedTask(executorId, message)));
    }

    @Override
    public Uni<Void> acknowledge(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return Uni.createFrom().voidItem();
        }

        // Executor ACK means "accepted from stream"; Redis stream ACK is only done
        // after result processing so a crashed executor remains recoverable.
        return redis.value(String.class, String.class).get(taskOwnerKey(taskId))
                .flatMap(executorId -> {
                    if (executorId == null || executorId.isBlank()) {
                        return Uni.createFrom().voidItem();
                    }
                    return redis.sortedSet(String.class, String.class)
                            .zadd(inFlightKey(executorId), nowMillis(), taskId)
                            .replaceWithVoid();
                });
    }

    @Override
    public Uni<Void> complete(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return Uni.createFrom().voidItem();
        }

        return redis.value(String.class, String.class).get(taskOwnerKey(taskId))
                .flatMap(executorId -> {
                    if (executorId == null || executorId.isBlank()) {
                        return Uni.createFrom().voidItem();
                    }

                    return redis.value(String.class, String.class).get(taskMessageKey(taskId))
                            .flatMap(messageId -> {
                                if (messageId == null || messageId.isBlank()) {
                                    return cleanupTaskIndexes(executorId, taskId);
                                }
                                return redis.stream(String.class, String.class, NodeExecutionTask.class)
                                        .xack(streamKey(executorId), GROUP_NAME, messageId)
                                        .replaceWithVoid()
                                        .chain(() -> cleanupTaskIndexes(executorId, taskId));
                            });
                });
    }

    private Uni<List<StreamMessage<String, String, NodeExecutionTask>>> poll(
            String executorId,
            String streamKey,
            String consumerName,
            int capacity) {

        return activeInFlightCount(executorId)
                .flatMap(inFlight -> {
                    int available = Math.max(0, capacity - inFlight);
                    if (available == 0) {
                        return Uni.createFrom()
                                .item(List.<StreamMessage<String, String, NodeExecutionTask>>of())
                                .onItem().delayIt().by(blockTimeout);
                    }
                    return reclaimStalePending(executorId, streamKey, consumerName, available)
                            .flatMap(reclaimed -> {
                                if (!reclaimed.isEmpty()) {
                                    return Uni.createFrom().item(reclaimed);
                                }
                                return readNew(streamKey, consumerName, available);
                            });
                });
    }

    private Uni<List<StreamMessage<String, String, NodeExecutionTask>>> reclaimStalePending(
            String executorId,
            String streamKey,
            String consumerName,
            int capacity) {

        int batchSize = Math.max(1, Math.min(capacity, Math.max(1, reclaimBatchSize)));
        return redis.stream(String.class, String.class, NodeExecutionTask.class)
                .xautoclaim(streamKey, GROUP_NAME, consumerName, safeReclaimIdleTimeout(), "0-0", batchSize)
                .map(ClaimedMessages::getMessages)
                .onItem().ifNull()
                .continueWith(List.<StreamMessage<String, String, NodeExecutionTask>>of())
                .flatMap(messages -> filterActiveAckLeases(executorId, messages));
    }

    private Uni<List<StreamMessage<String, String, NodeExecutionTask>>> readNew(
            String streamKey,
            String consumerName,
            int capacity) {

        return redis.stream(String.class, String.class, NodeExecutionTask.class)
                .xreadgroup(GROUP_NAME,
                        consumerName,
                        Map.of(streamKey, ">"),
                        new XReadGroupArgs().count(capacity).block(blockTimeout))
                .onItem().ifNull()
                .continueWith(List.<StreamMessage<String, String, NodeExecutionTask>>of());
    }

    private Uni<StreamedTask> toStreamedTask(
            String executorId,
            StreamMessage<String, String, NodeExecutionTask> message) {

        NodeExecutionTask task = message.payload().get(PAYLOAD_FIELD);
        if (task == null) {
            return Uni.createFrom().failure(new IllegalStateException(
                    "Redis gRPC task stream message has no payload: " + message.id()));
        }

        String taskId = GrpcTaskStreamBroker.taskId(task);
        return rememberDelivery(executorId, taskId, message.id())
                .replaceWith(new StreamedTask(taskId, task));
    }

    @SuppressWarnings("unchecked")
    private StreamMessage<String, String, NodeExecutionTask> castMessage(Object message) {
        return (StreamMessage<String, String, NodeExecutionTask>) message;
    }

    private Uni<Void> rememberAssignment(String executorId, String taskId, String messageId) {
        return redis.value(String.class, String.class).set(taskOwnerKey(taskId), executorId)
                .chain(() -> rememberMessage(taskId, messageId))
                .replaceWithVoid();
    }

    private Uni<Boolean> claimAssignment(String taskId, String executorId) {
        return redis.value(String.class, String.class)
                .setAndChanged(taskOwnerKey(taskId), executorId,
                        new SetArgs().nx().ex(safeAssignmentClaimTtl()));
    }

    private Uni<Void> rememberMessage(String taskId, String messageId) {
        return redis.value(String.class, String.class)
                .set(taskMessageKey(taskId), messageId)
                .replaceWithVoid();
    }

    private Uni<Void> rememberDelivery(String executorId, String taskId, String messageId) {
        return rememberAssignment(executorId, taskId, messageId)
                .chain(() -> redis.sortedSet(String.class, String.class)
                        .zadd(inFlightKey(executorId), nowMillis(), taskId))
                .replaceWithVoid();
    }

    private Uni<List<StreamMessage<String, String, NodeExecutionTask>>> filterActiveAckLeases(
            String executorId,
            List<StreamMessage<String, String, NodeExecutionTask>> messages) {

        if (messages == null || messages.isEmpty()) {
            return Uni.createFrom().item(List.of());
        }

        List<String> taskIds = messages.stream()
                .map(this::taskId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (taskIds.isEmpty()) {
            return Uni.createFrom().item(messages);
        }

        double activeAfter = nowMillis() - safeReclaimIdleTimeout().toMillis();
        return redis.sortedSet(String.class, String.class)
                .zmscore(inFlightKey(executorId), taskIds.toArray(String[]::new))
                .map(scores -> filterActiveAckLeases(messages, taskScoreMap(taskIds, scores), activeAfter));
    }

    List<StreamMessage<String, String, NodeExecutionTask>> filterActiveAckLeases(
            List<StreamMessage<String, String, NodeExecutionTask>> messages,
            Map<String, Double> taskScores,
            double activeAfter) {

        return messages.stream()
                .filter(message -> !hasActiveAckLease(message, taskScores, activeAfter))
                .toList();
    }

    private boolean hasActiveAckLease(
            StreamMessage<String, String, NodeExecutionTask> message,
            Map<String, Double> taskScores,
            double activeAfter) {

        String taskId = taskId(message);
        if (taskId == null) {
            return false;
        }

        Double score = taskScores != null ? taskScores.get(taskId) : null;
        if (score == null || score < activeAfter) {
            return false;
        }

        LOG.debug("Skipped Redis reclaimed gRPC stream task {}; ACK lease is still active", taskId);
        return true;
    }

    private Map<String, Double> taskScoreMap(List<String> taskIds, List<Double> scores) {
        Map<String, Double> taskScores = new HashMap<>();
        for (int i = 0; i < taskIds.size(); i++) {
            Double score = scores != null && i < scores.size() ? scores.get(i) : null;
            if (score != null) {
                taskScores.put(taskIds.get(i), score);
            }
        }
        return taskScores;
    }

    private String taskId(StreamMessage<String, String, NodeExecutionTask> message) {
        if (message == null || message.payload() == null) {
            return null;
        }
        NodeExecutionTask task = message.payload().get(PAYLOAD_FIELD);
        return task != null ? GrpcTaskStreamBroker.taskId(task) : null;
    }

    private Uni<Void> cleanupTaskIndexes(String executorId, String taskId) {
        return redis.sortedSet(String.class, String.class)
                .zrem(inFlightKey(executorId), taskId)
                .replaceWithVoid()
                .chain(() -> redis.key().del(taskOwnerKey(taskId)).replaceWithVoid())
                .chain(() -> redis.key().del(taskMessageKey(taskId)).replaceWithVoid());
    }

    private Uni<Void> releaseAssignmentClaim(String taskId) {
        return redis.key().del(taskOwnerKey(taskId)).replaceWithVoid();
    }

    private Uni<Integer> activeInFlightCount(String executorId) {
        double activeAfter = nowMillis() - safeReclaimIdleTimeout().toMillis();
        return redis.sortedSet(String.class, String.class)
                .zcount(inFlightKey(executorId), ScoreRange.from(activeAfter, Double.MAX_VALUE))
                .map(Long::intValue);
    }

    private Uni<Void> ensureGroup(String streamKey) {
        if (initializedStreams.contains(streamKey)) {
            return Uni.createFrom().voidItem();
        }

        return redis.stream(String.class, String.class, NodeExecutionTask.class)
                .xgroupCreate(streamKey, GROUP_NAME, "0", new XGroupCreateArgs().mkstream())
                .onFailure().recoverWithUni(error -> {
                    String message = error.getMessage();
                    if (message != null && message.contains("BUSYGROUP")) {
                        return Uni.createFrom().voidItem();
                    }
                    return Uni.createFrom().failure(error);
                })
                .invoke(() -> initializedStreams.add(streamKey))
                .replaceWithVoid();
    }

    private String consumerName(String executorId) {
        return executorId + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String streamKey(String executorId) {
        return STREAM_PREFIX + executorId + ":tasks";
    }

    private String inFlightKey(String executorId) {
        return IN_FLIGHT_PREFIX + executorId;
    }

    private String taskOwnerKey(String taskId) {
        return TASK_OWNER_PREFIX + taskId;
    }

    private String taskMessageKey(String taskId) {
        return TASK_MESSAGE_PREFIX + taskId;
    }

    private Duration safeReclaimIdleTimeout() {
        if (reclaimIdleTimeout == null || reclaimIdleTimeout.isNegative() || reclaimIdleTimeout.isZero()) {
            return Duration.ofMinutes(5);
        }
        return reclaimIdleTimeout;
    }

    private Duration safeAssignmentClaimTtl() {
        if (assignmentClaimTtl == null || assignmentClaimTtl.isNegative() || assignmentClaimTtl.isZero()) {
            return Duration.ofSeconds(30);
        }
        return assignmentClaimTtl;
    }

    private double nowMillis() {
        return (double) System.currentTimeMillis();
    }
}
