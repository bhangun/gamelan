package tech.kayys.gamelan.scheduler.redis;

import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.sortedset.ScoreRange;
import io.quarkus.redis.datasource.sortedset.ZRangeArgs;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.arc.properties.IfBuildProperty;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import tech.kayys.gamelan.engine.event.EventPublisher;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;
import tech.kayys.gamelan.scheduler.RetryManager;

@ApplicationScoped
@IfBuildProperty(name = "gamelan.scheduler.mode", stringValue = "redis")
public class RedisRetryManager implements RetryManager {

    private static final Logger LOG = LoggerFactory.getLogger(RedisRetryManager.class);
    private static final String RETRY_ZSET = "workflow:tasks:retry:zset";

    @Inject
    ReactiveRedisDataSource redis;

    @Inject
    EventPublisher eventPublisher;

    @Override
    public Uni<Void> scheduleRetry(WorkflowRunId runId, NodeId nodeId, Duration delay) {
        long executeAt = Instant.now().plus(delay).toEpochMilli();
        String value = runId.value() + ":" + nodeId.value();

        LOG.info("Scheduling Redis retry run={}, node={} at {}",
                runId.value(), nodeId.value(), executeAt);

        return redis.sortedSet(String.class)
                .zadd(RETRY_ZSET, executeAt, value)
                .replaceWithVoid();
    }

    @Scheduled(every = "5s")
    void processRetryQueue() {
        long now = Instant.now().toEpochMilli();
        ScoreRange<Double> range = ScoreRange.from(0.0, (double) now);
        ZRangeArgs args = new ZRangeArgs().limit(0, 50);

        redis.sortedSet(String.class, String.class)
                .zrangebyscore(RETRY_ZSET, range, args)
                .subscribe().with(entries -> {
                    for (String entry : entries) {
                        handleRetryEntry(entry);
                    }
                });
    }

    private void handleRetryEntry(String entry) {
        String[] parts = entry.split(":");
        if (parts.length != 2) return;

        WorkflowRunId runId = WorkflowRunId.of(parts[0]);
        NodeId nodeId = NodeId.of(parts[1]);

        redis.sortedSet(String.class, String.class)
                .zrem(RETRY_ZSET, entry)
                .subscribe().with(ignored -> {
                    LOG.info("Retrying node {} for run {}", nodeId.value(), runId.value());
                    eventPublisher.publishRetry(runId, nodeId)
                            .subscribe().with(v -> {}, err -> LOG.error("Retry publish failed", err));
                });
    }
}
