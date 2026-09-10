package tech.kayys.gamelan.scheduler.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class RedisTaskQueueRecoveryTest {

    @Test
    void readBatchSizeFallsBackWhenInvalid() {
        assertEquals(10, RedisTaskQueue.effectiveReadBatchSize(0));
        assertEquals(10, RedisTaskQueue.effectiveReadBatchSize(-1));
        assertEquals(25, RedisTaskQueue.effectiveReadBatchSize(25));
    }

    @Test
    void reclaimBatchSizeIsBoundedByReadBatchSize() {
        assertEquals(10, RedisTaskQueue.effectiveReclaimBatchSize(10, 100));
        assertEquals(5, RedisTaskQueue.effectiveReclaimBatchSize(10, 5));
        assertEquals(10, RedisTaskQueue.effectiveReclaimBatchSize(10, 0));
        assertEquals(10, RedisTaskQueue.effectiveReclaimBatchSize(0, 0));
    }

    @Test
    void safeDurationFallsBackWhenInvalid() {
        Duration fallback = Duration.ofSeconds(30);

        assertEquals(fallback, RedisTaskQueue.safeDuration(null, fallback));
        assertEquals(fallback, RedisTaskQueue.safeDuration(Duration.ZERO, fallback));
        assertEquals(fallback, RedisTaskQueue.safeDuration(Duration.ofSeconds(-1), fallback));
        assertEquals(Duration.ofSeconds(5), RedisTaskQueue.safeDuration(Duration.ofSeconds(5), fallback));
    }
}
