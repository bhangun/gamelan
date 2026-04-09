package tech.kayys.gamelan.scheduler.redis;

import java.time.Duration;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.stream.XGroupCreateArgs;
import io.quarkus.redis.datasource.stream.XReadGroupArgs;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import tech.kayys.gamelan.engine.node.NodeExecutionTask;
import tech.kayys.gamelan.scheduler.TaskQueue;

/**
 * High-performance Task Queue using Redis Streams
 */
@ApplicationScoped
@jakarta.enterprise.inject.Alternative
@jakarta.annotation.Priority(1)
public class RedisTaskQueue implements TaskQueue {

    private static final Logger LOG = LoggerFactory.getLogger(RedisTaskQueue.class);
    
    public static final String STREAM_KEY = "workflow:tasks:stream";
    public static final String GROUP_NAME = "gamelan-engine-group";
    private static final String CONSUMER_NAME = "engine-" + java.util.UUID.randomUUID().toString().substring(0, 8);

    @Inject
    ReactiveRedisDataSource redis;

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
        return redis.stream(String.class, String.class, NodeExecutionTask.class)
            .xadd(STREAM_KEY, Map.of("payload", task))
            .invoke(id -> LOG.debug("Task enqueued to Redis Stream: {} (ID: {})", task.nodeId().value(), id))
            .replaceWithVoid();
    }

    @Override
    public Multi<QueuedTask> consume() {
        return Multi.createBy().repeating()
            .uni(() -> redis.stream(String.class, String.class, NodeExecutionTask.class)
                .xreadgroup(GROUP_NAME, CONSUMER_NAME, 
                    Map.of(STREAM_KEY, ">"), 
                    new XReadGroupArgs().count(10).block(Duration.ofSeconds(1)))
            )
            .whilst(tasks -> true)
            .onItem().disjoint()
            .map(m -> {
                @SuppressWarnings("unchecked")
                var message = (io.quarkus.redis.datasource.stream.StreamMessage<String, String, NodeExecutionTask>) m;
                return new QueuedTask(message.id(), message.payload().get("payload"));
            });
    }

    @Override
    public Uni<Void> acknowledge(String messageId) {
        return redis.stream(String.class, String.class, NodeExecutionTask.class)
            .xack(STREAM_KEY, GROUP_NAME, messageId)
            .replaceWithVoid();
    }
}
