package tech.kayys.gamelan.scheduler.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.sortedset.ReactiveSortedSetCommands;
import io.quarkus.redis.datasource.sortedset.ScoreRange;
import io.quarkus.redis.datasource.sortedset.ZRangeArgs;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.redis.client.Response;
import tech.kayys.gamelan.engine.context.WorkflowContext;
import tech.kayys.gamelan.engine.event.EventPublisher;
import tech.kayys.gamelan.engine.event.ExecutionEvent;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;
import tech.kayys.gamelan.scheduler.RetryEntries;

@SuppressWarnings("unchecked")
class RedisRetryManagerTest {
    private static final String RETRY_ZSET = "workflow:tasks:retry:zset";
    private static final String PROCESSING_ZSET = "workflow:tasks:retry:processing:zset";

    private RedisRetryManager manager;
    private ReactiveRedisDataSource redis;
    private ReactiveSortedSetCommands<String, String> sortedSet;
    private RecordingEventPublisher publisher;

    @BeforeEach
    void setUp() {
        redis = mock(ReactiveRedisDataSource.class);
        sortedSet = mock(ReactiveSortedSetCommands.class);
        publisher = new RecordingEventPublisher();
        manager = new RedisRetryManager();
        manager.redis = redis;
        manager.eventPublisher = publisher;
        when(redis.sortedSet(String.class, String.class)).thenReturn(sortedSet);
    }

    @Test
    void scheduleRetry_preservesEarliestRedisScoreWithTenantAwareEntry() {
        WorkflowRunId runId = WorkflowRunId.of("run-1");
        TenantId tenantId = TenantId.of("tenant-1");
        NodeId nodeId = NodeId.of("node-1");
        Response scheduled = redisResponse(1L);
        when(redis.execute(eq("EVAL"), any(String[].class))).thenReturn(Uni.createFrom().item(scheduled));
        ArgumentCaptor<String[]> redisArgs = ArgumentCaptor.forClass(String[].class);
        long before = Instant.now().plusMillis(250).toEpochMilli();

        manager.scheduleRetry(runId, tenantId, nodeId, 2, Duration.ofMillis(250)).await().indefinitely();

        long after = Instant.now().plusMillis(250).toEpochMilli();
        verify(redis).execute(eq("EVAL"), redisArgs.capture());
        String[] args = redisArgs.getValue();
        assertTrue(args[0].contains("ZSCORE"));
        assertTrue(args[0].contains("KEYS[2]"));
        assertTrue(args[0].contains("processingScore"));
        assertTrue(args[0].contains("tonumber(ARGV[2]) < tonumber(score)"));
        assertEquals("2", args[1]);
        assertEquals(RETRY_ZSET, args[2]);
        assertEquals(PROCESSING_ZSET, args[3]);
        RetryEntries.Entry entry = RetryEntries.decode(args[4]).orElseThrow();
        assertEquals(runId, entry.runId());
        assertEquals(tenantId, entry.tenantId());
        assertEquals(nodeId, entry.nodeId());
        assertEquals(2, entry.attempt());
        long executeAt = Long.parseLong(args[5]);
        assertTrue(executeAt >= before);
        assertTrue(executeAt <= after);
    }

    @Test
    void processRetryQueue_skipsDuplicateEntryWhilePublishIsInFlight() throws Exception {
        WorkflowRunId runId = WorkflowRunId.of("run-1");
        NodeId nodeId = NodeId.of("node-1");
        String entry = RetryEntries.encode(runId, nodeId);
        CompletableFuture<Void> publishGate = new CompletableFuture<>();
        publisher.publishGate = publishGate;
        when(sortedSet.zrangebyscore(eq(PROCESSING_ZSET), any(ScoreRange.class), any(ZRangeArgs.class)))
                .thenReturn(Uni.createFrom().item(List.of()));
        when(sortedSet.zrangebyscore(eq(RETRY_ZSET), any(ScoreRange.class), any(ZRangeArgs.class)))
                .thenReturn(Uni.createFrom().item(List.of(entry)));
        Uni<Response> claimed = Uni.createFrom().item(redisResponse(1L));
        when(redis.execute(eq("EVAL"), any(String[].class))).thenReturn(claimed);
        when(sortedSet.zrem(PROCESSING_ZSET, entry)).thenReturn(Uni.createFrom().item(1));

        manager.processRetryQueue();
        assertTrue(publisher.publishStarted.await(2, TimeUnit.SECONDS));

        manager.processRetryQueue();

        assertEquals(1, publisher.retryPublishCount.get());
        assertFalse(publisher.zremStarted);

        publishGate.complete(null);
        assertTrue(publisher.publishCompleted.await(2, TimeUnit.SECONDS));
        verify(sortedSet).zrem(PROCESSING_ZSET, entry);
    }

    @Test
    void processRetryQueue_releasesInFlightEntryAfterPublishFailure() throws Exception {
        WorkflowRunId runId = WorkflowRunId.of("run-1");
        NodeId nodeId = NodeId.of("node-1");
        String entry = RetryEntries.encode(runId, nodeId);
        publisher.failuresRemaining = 1;
        when(sortedSet.zrangebyscore(eq(PROCESSING_ZSET), any(ScoreRange.class), any(ZRangeArgs.class)))
                .thenReturn(Uni.createFrom().item(List.of()));
        when(sortedSet.zrangebyscore(eq(RETRY_ZSET), any(ScoreRange.class), any(ZRangeArgs.class)))
                .thenReturn(Uni.createFrom().item(List.of(entry)));
        Uni<Response> firstClaim = Uni.createFrom().item(redisResponse(1L));
        Uni<Response> requeued = Uni.createFrom().item(redisResponse(1L));
        Uni<Response> secondClaim = Uni.createFrom().item(redisResponse(1L));
        when(redis.execute(eq("EVAL"), any(String[].class)))
                .thenReturn(
                        firstClaim,
                        requeued,
                        secondClaim);
        when(sortedSet.zrem(PROCESSING_ZSET, entry)).thenReturn(Uni.createFrom().item(1));

        manager.processRetryQueue();
        assertTrue(publisher.publishCompleted.await(2, TimeUnit.SECONDS));

        publisher.publishCompleted = new CountDownLatch(1);
        manager.processRetryQueue();

        assertTrue(publisher.publishCompleted.await(2, TimeUnit.SECONDS));
        assertEquals(2, publisher.retryPublishCount.get());
    }

    @Test
    void processRetryQueue_skipsEntryClaimedByAnotherRuntimeAndReleasesLocalGuard() throws Exception {
        WorkflowRunId runId = WorkflowRunId.of("run-1");
        NodeId nodeId = NodeId.of("node-1");
        String entry = RetryEntries.encode(runId, nodeId);
        when(sortedSet.zrangebyscore(eq(PROCESSING_ZSET), any(ScoreRange.class), any(ZRangeArgs.class)))
                .thenReturn(Uni.createFrom().item(List.of()));
        when(sortedSet.zrangebyscore(eq(RETRY_ZSET), any(ScoreRange.class), any(ZRangeArgs.class)))
                .thenReturn(Uni.createFrom().item(List.of(entry)));
        Uni<Response> alreadyClaimed = Uni.createFrom().item(redisResponse(0L));
        Uni<Response> claimed = Uni.createFrom().item(redisResponse(1L));
        when(redis.execute(eq("EVAL"), any(String[].class)))
                .thenReturn(
                        alreadyClaimed,
                        claimed);
        when(sortedSet.zrem(PROCESSING_ZSET, entry)).thenReturn(Uni.createFrom().item(1));

        manager.processRetryQueue();
        assertEquals(0, publisher.retryPublishCount.get());

        manager.processRetryQueue();

        assertTrue(publisher.publishCompleted.await(2, TimeUnit.SECONDS));
        assertEquals(1, publisher.retryPublishCount.get());
    }

    @Test
    void processRetryQueue_restoresExpiredProcessingClaimBeforeScanningDueRetries() {
        String entry = RetryEntries.encode(WorkflowRunId.of("run-1"), NodeId.of("node-1"));
        when(sortedSet.zrangebyscore(eq(PROCESSING_ZSET), any(ScoreRange.class), any(ZRangeArgs.class)))
                .thenReturn(Uni.createFrom().item(List.of(entry)));
        when(sortedSet.zrangebyscore(eq(RETRY_ZSET), any(ScoreRange.class), any(ZRangeArgs.class)))
                .thenReturn(Uni.createFrom().item(List.of()));
        Uni<Response> restored = Uni.createFrom().item(redisResponse(1L));
        when(redis.execute(eq("EVAL"), any(String[].class))).thenReturn(restored);
        ArgumentCaptor<String[]> redisArgs = ArgumentCaptor.forClass(String[].class);

        manager.processRetryQueue();

        assertEquals(0, publisher.retryPublishCount.get());
        verify(redis).execute(eq("EVAL"), redisArgs.capture());
        String[] args = redisArgs.getValue();
        assertEquals(PROCESSING_ZSET, args[2]);
        assertEquals(RETRY_ZSET, args[3]);
        assertEquals(entry, args[4]);
    }

    private static final class RecordingEventPublisher implements EventPublisher {
        private final AtomicInteger retryPublishCount = new AtomicInteger();
        private CountDownLatch publishStarted = new CountDownLatch(1);
        private CountDownLatch publishCompleted = new CountDownLatch(1);
        private CompletableFuture<Void> publishGate;
        private int failuresRemaining;
        private boolean zremStarted;

        @Override
        public void publish(String eventType, Object payload, WorkflowContext workflowContext) {
        }

        @Override
        public void publishSystem(String eventType, Object payload) {
        }

        @Override
        public Uni<Void> publish(List<ExecutionEvent> events) {
            return Uni.createFrom().voidItem();
        }

        @Override
        public Uni<Void> publishRetry(WorkflowRunId runId, NodeId nodeId) {
            return publishRetry(runId, null, nodeId);
        }

        @Override
        public Uni<Void> publishRetry(WorkflowRunId runId, TenantId tenantId, NodeId nodeId) {
            retryPublishCount.incrementAndGet();
            publishStarted.countDown();
            if (failuresRemaining > 0) {
                failuresRemaining--;
                publishCompleted.countDown();
                return Uni.createFrom().failure(new IllegalStateException("event bus unavailable"));
            }
            if (publishGate != null) {
                return Uni.createFrom().completionStage(publishGate)
                        .invoke(() -> {
                            zremStarted = true;
                            publishCompleted.countDown();
                        })
                        .replaceWithVoid();
            }
            publishCompleted.countDown();
            return Uni.createFrom().voidItem();
        }
    }

    private static Response redisResponse(long value) {
        Response response = mock(Response.class);
        when(response.toLong()).thenReturn(value);
        return response;
    }
}
