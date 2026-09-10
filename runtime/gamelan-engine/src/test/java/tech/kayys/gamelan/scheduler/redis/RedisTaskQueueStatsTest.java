package tech.kayys.gamelan.scheduler.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import tech.kayys.gamelan.scheduler.TaskQueue;

class RedisTaskQueueStatsTest {

    private static final Instant OBSERVED_AT = Instant.parse("2026-05-29T00:00:00Z");

    @Test
    void statsMapRedisGroupLagToAvailableAndPendingToLeased() {
        TaskQueue.QueueStats stats = RedisTaskQueue.statsFromGroupValues(
                RedisTaskQueue.GROUP_NAME,
                4L,
                6L,
                OBSERVED_AT);

        assertTrue(stats.known());
        assertEquals(OBSERVED_AT, stats.observedAt());
        assertEquals(10, stats.total());
        assertEquals(6, stats.available());
        assertEquals(4, stats.leased());
        assertEquals(0, stats.expired());
        assertEquals(0, stats.unreadable());
        assertEquals(6, stats.claimable());
        assertEquals(TaskQueue.QueueHealth.BACKLOG, stats.health());
    }

    @Test
    void statsReportActiveWhenOnlyPendingWorkExists() {
        TaskQueue.QueueStats stats = RedisTaskQueue.statsFromGroupValues(
                RedisTaskQueue.GROUP_NAME,
                3L,
                0L,
                OBSERVED_AT);

        assertTrue(stats.known());
        assertEquals(3, stats.total());
        assertEquals(0, stats.available());
        assertEquals(3, stats.leased());
        assertEquals(0, stats.claimable());
        assertEquals(TaskQueue.QueueHealth.ACTIVE, stats.health());
    }

    @Test
    void statsReturnUnknownWhenGroupOrLagIsUnavailable() {
        TaskQueue.QueueStats wrongGroup = RedisTaskQueue.statsFromGroupValues(
                "other-group",
                1L,
                2L,
                OBSERVED_AT);
        TaskQueue.QueueStats missingLag = RedisTaskQueue.statsFromGroupValues(
                RedisTaskQueue.GROUP_NAME,
                1L,
                null,
                OBSERVED_AT);

        assertFalse(wrongGroup.known());
        assertEquals(TaskQueue.QueueHealth.UNKNOWN, wrongGroup.health());
        assertEquals(OBSERVED_AT, wrongGroup.observedAt());
        assertEquals(-1, wrongGroup.total());
        assertFalse(missingLag.known());
        assertEquals(TaskQueue.QueueHealth.UNKNOWN, missingLag.health());
    }

    @Test
    void statsClampNegativeRedisValues() {
        TaskQueue.QueueStats stats = RedisTaskQueue.statsFromGroupValues(
                RedisTaskQueue.GROUP_NAME,
                -1L,
                -1L,
                OBSERVED_AT);

        assertTrue(stats.known());
        assertEquals(0, stats.total());
        assertEquals(0, stats.available());
        assertEquals(0, stats.leased());
        assertEquals(TaskQueue.QueueHealth.IDLE, stats.health());
    }
}
