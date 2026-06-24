package tech.kayys.gamelan.scheduler.redis;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.sortedset.ScoreRange;
import io.quarkus.redis.datasource.sortedset.ZRangeArgs;
import io.quarkus.scheduler.Scheduled;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.redis.client.Response;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import tech.kayys.gamelan.engine.event.EventPublisher;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;
import tech.kayys.gamelan.scheduler.RetryEntries;
import tech.kayys.gamelan.scheduler.RetryManager;

@ApplicationScoped
@jakarta.enterprise.inject.Alternative
@jakarta.annotation.Priority(1)
public class RedisRetryManager implements RetryManager {

    private static final Logger LOG = LoggerFactory.getLogger(RedisRetryManager.class);
    private static final String RETRY_ZSET = "workflow:tasks:retry:zset";
    private static final String PROCESSING_ZSET = "workflow:tasks:retry:processing:zset";
    private static final int DEFAULT_BATCH_SIZE = 50;
    private static final Duration DEFAULT_CLAIM_TTL = Duration.ofSeconds(30);
    private static final String SCHEDULE_EARLIEST_SCRIPT = """
            local processingScore = redis.call('ZSCORE', KEYS[2], ARGV[1])
            if processingScore then
              return 0
            end
            local score = redis.call('ZSCORE', KEYS[1], ARGV[1])
            if (not score) or tonumber(ARGV[2]) < tonumber(score) then
              redis.call('ZADD', KEYS[1], tonumber(ARGV[2]), ARGV[1])
              return 1
            end
            return 0
            """;
    private static final String CLAIM_DUE_SCRIPT = """
            local score = redis.call('ZSCORE', KEYS[1], ARGV[1])
            if score and tonumber(score) <= tonumber(ARGV[2]) then
              redis.call('ZREM', KEYS[1], ARGV[1])
              redis.call('ZADD', KEYS[2], tonumber(ARGV[3]), ARGV[1])
              return 1
            end
            return 0
            """;
    private static final String RESTORE_EXPIRED_CLAIM_SCRIPT = """
            local score = redis.call('ZSCORE', KEYS[1], ARGV[1])
            if score and tonumber(score) <= tonumber(ARGV[2]) then
              redis.call('ZREM', KEYS[1], ARGV[1])
              redis.call('ZADD', KEYS[2], tonumber(ARGV[2]), ARGV[1])
              return 1
            end
            return 0
            """;
    private static final String REQUEUE_CLAIM_SCRIPT = """
            redis.call('ZREM', KEYS[1], ARGV[1])
            redis.call('ZADD', KEYS[2], tonumber(ARGV[2]), ARGV[1])
            return 1
            """;

    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    @Inject
    ReactiveRedisDataSource redis;

    @Inject
    EventPublisher eventPublisher;

    @ConfigProperty(name = "gamelan.retry.redis.batch-size", defaultValue = "50")
    int batchSize = DEFAULT_BATCH_SIZE;

    @ConfigProperty(name = "gamelan.retry.redis.claim-ttl", defaultValue = "30s")
    Duration claimTtl = DEFAULT_CLAIM_TTL;

    @Override
    public Uni<Void> scheduleRetry(WorkflowRunId runId, NodeId nodeId, Duration delay) {
        return scheduleRetry(runId, null, nodeId, delay);
    }

    @Override
    public Uni<Void> scheduleRetry(WorkflowRunId runId, TenantId tenantId, NodeId nodeId, Duration delay) {
        return scheduleRetry(runId, tenantId, nodeId, null, delay);
    }

    @Override
    public Uni<Void> scheduleRetry(WorkflowRunId runId, TenantId tenantId, NodeId nodeId, int attempt,
            Duration delay) {
        return scheduleRetry(runId, tenantId, nodeId, Integer.valueOf(attempt), delay);
    }

    private Uni<Void> scheduleRetry(WorkflowRunId runId, TenantId tenantId, NodeId nodeId, Integer attempt,
            Duration delay) {
        Duration safeDelay = delay != null && !delay.isNegative() ? delay : Duration.ZERO;
        long executeAt = Instant.now().plus(safeDelay).toEpochMilli();
        String value = attempt != null
                ? RetryEntries.encode(runId, tenantId, nodeId, attempt)
                : RetryEntries.encode(runId, tenantId, nodeId);

        LOG.info("Scheduling Redis retry run={}, tenant={}, node={}, attempt={} at {}",
                runId.value(), tenantValue(tenantId), nodeId.value(), attemptValue(attempt), executeAt);

        return redis.execute(
                "EVAL",
                SCHEDULE_EARLIEST_SCRIPT,
                "2",
                RETRY_ZSET,
                PROCESSING_ZSET,
                value,
                Long.toString(executeAt))
                .replaceWithVoid();
    }

    @Scheduled(every = "{gamelan.retry.scan-interval:5s}")
    void processRetryQueue() {
        long now = Instant.now().toEpochMilli();
        restoreExpiredClaims(now)
                .chain(() -> scanDueRetries(now))
                .subscribe().with(
                        ignored -> {
                        },
                        error -> {
                            LOG.warn("Redis retry queue processing failed: {}", error.getMessage());
                            LOG.debug("Redis retry queue processing failure details", error);
                        });
    }

    private Uni<Void> scanDueRetries(long now) {
        ScoreRange<Double> range = ScoreRange.from(0.0, (double) now);
        ZRangeArgs args = new ZRangeArgs().limit(0, effectiveBatchSize());

        return redis.sortedSet(String.class, String.class)
                .zrangebyscore(RETRY_ZSET, range, args)
                .invoke(entries -> {
                    if (entries == null || entries.isEmpty()) {
                        return;
                    }
                    for (String entry : entries) {
                        handleRetryEntry(entry, now);
                    }
                })
                .replaceWithVoid();
    }

    private Uni<Void> restoreExpiredClaims(long now) {
        ScoreRange<Double> range = ScoreRange.from(0.0, (double) now);
        ZRangeArgs args = new ZRangeArgs().limit(0, effectiveBatchSize());

        return redis.sortedSet(String.class, String.class)
                .zrangebyscore(PROCESSING_ZSET, range, args)
                .flatMap(entries -> {
                    if (entries == null || entries.isEmpty()) {
                        return Uni.createFrom().voidItem();
                    }
                    List<Uni<Void>> restores = entries.stream()
                            .filter(entry -> entry != null)
                            .map(entry -> restoreExpiredClaim(entry, now))
                            .toList();
                    if (restores.isEmpty()) {
                        return Uni.createFrom().voidItem();
                    }
                    return Uni.combine().all().unis(
                            restores)
                            .discardItems();
                });
    }

    private Uni<Void> restoreExpiredClaim(String entry, long now) {
        return redis.execute(
                "EVAL",
                RESTORE_EXPIRED_CLAIM_SCRIPT,
                "2",
                PROCESSING_ZSET,
                RETRY_ZSET,
                entry,
                Long.toString(now))
                .invoke(response -> {
                    if (redisReturnedOne(response)) {
                        LOG.warn("Restored expired Redis retry claim: {}", entry);
                    }
                })
                .replaceWithVoid();
    }

    private void handleRetryEntry(String entry, long now) {
        if (entry == null) {
            LOG.warn("Ignoring null Redis retry entry");
            return;
        }
        if (!inFlight.add(entry)) {
            LOG.debug("Skipping Redis retry entry already in-flight: {}", entry);
            return;
        }

        RetryEntries.decode(entry).ifPresentOrElse(decoded -> claimAndPublish(entry, decoded, now),
                () -> redis.sortedSet(String.class, String.class)
                        .zrem(RETRY_ZSET, entry)
                        .subscribe().with(
                                ignored -> {
                                    inFlight.remove(entry);
                                    LOG.warn("Discarded invalid Redis retry entry: {}", entry);
                                },
                                error -> {
                                    inFlight.remove(entry);
                                    LOG.error("Failed to discard invalid Redis retry entry: {}", entry, error);
                                }));
    }

    private void claimAndPublish(String entry, RetryEntries.Entry decoded, long now) {
        long leaseUntil = now + effectiveClaimTtl().toMillis();
        claimDueEntry(entry, now, leaseUntil)
                .subscribe().with(claimed -> {
                    if (!claimed) {
                        inFlight.remove(entry);
                        LOG.debug("Skipping Redis retry entry already claimed by another runtime: {}", entry);
                        return;
                    }
                    publishRetry(entry, decoded);
                }, error -> {
                    inFlight.remove(entry);
                    LOG.warn("Failed to claim Redis retry entry {}: {}", entry, error.getMessage());
                    LOG.debug("Redis retry claim failure details", error);
                });
    }

    private Uni<Boolean> claimDueEntry(String entry, long now, long leaseUntil) {
        return redis.execute(
                "EVAL",
                CLAIM_DUE_SCRIPT,
                "2",
                RETRY_ZSET,
                PROCESSING_ZSET,
                entry,
                Long.toString(now),
                Long.toString(leaseUntil))
                .map(RedisRetryManager::redisReturnedOne);
    }

    private void publishRetry(String entry, RetryEntries.Entry decoded) {
        WorkflowRunId runId = decoded.runId();
        TenantId tenantId = decoded.tenantId();
        NodeId nodeId = decoded.nodeId();
        Integer attempt = decoded.attempt();

        LOG.info("Retrying node {} for run {} tenant={} attempt={}", nodeId.value(), runId.value(),
                tenantValue(tenantId), attemptValue(attempt));
        publishRetryEvent(runId, tenantId, nodeId, attempt)
                .call(() -> ackClaim(entry))
                .subscribe().with(
                        ignored -> {
                            inFlight.remove(entry);
                        },
                        error -> {
                            requeueClaim(entry, Instant.now().toEpochMilli())
                                    .subscribe().with(
                                            ignored -> inFlight.remove(entry),
                                            requeueError -> {
                                                inFlight.remove(entry);
                                                LOG.error(
                                                        "Retry publish failed and Redis retry claim could not be requeued entry={}: {}",
                                                        entry,
                                                        requeueError.getMessage());
                                                LOG.debug("Redis retry claim requeue failure details", requeueError);
                                            });
                            LOG.warn("Retry publish/ack failed; requeued Redis retry entry for redelivery error={}: {}",
                                    error.getClass().getName(),
                                    error.getMessage());
                            LOG.debug("Redis retry publish/ack failure details", error);
                        });
    }

    private Uni<Void> ackClaim(String entry) {
        return redis.sortedSet(String.class, String.class)
                .zrem(PROCESSING_ZSET, entry)
                .replaceWithVoid();
    }

    private Uni<Void> requeueClaim(String entry, long now) {
        return redis.execute(
                "EVAL",
                REQUEUE_CLAIM_SCRIPT,
                "2",
                PROCESSING_ZSET,
                RETRY_ZSET,
                entry,
                Long.toString(now))
                .replaceWithVoid();
    }

    private int effectiveBatchSize() {
        return batchSize > 0 ? batchSize : DEFAULT_BATCH_SIZE;
    }

    private Duration effectiveClaimTtl() {
        return claimTtl != null && !claimTtl.isNegative() && !claimTtl.isZero()
                ? claimTtl
                : DEFAULT_CLAIM_TTL;
    }

    private static boolean redisReturnedOne(Response response) {
        Long value = response != null ? response.toLong() : null;
        return value != null && value == 1L;
    }

    private static String tenantValue(TenantId tenantId) {
        return tenantId != null ? tenantId.value() : "";
    }

    private Uni<Void> publishRetryEvent(WorkflowRunId runId, TenantId tenantId, NodeId nodeId, Integer attempt) {
        return attempt != null
                ? eventPublisher.publishRetry(runId, tenantId, nodeId, attempt)
                : eventPublisher.publishRetry(runId, tenantId, nodeId);
    }

    private static String attemptValue(Integer attempt) {
        return attempt != null ? attempt.toString() : "";
    }
}
