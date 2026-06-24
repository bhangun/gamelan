package tech.kayys.gamelan.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class TaskQueueStatsTest {

    private static final Instant OBSERVED_AT = Instant.parse("2026-05-29T00:00:00Z");

    @Test
    void knownStatsDeriveClaimableHealthAndObservedAt() {
        TaskQueue.QueueStats idle = TaskQueue.QueueStats.knownAt(OBSERVED_AT, 0, 0, 0, 0, 0);
        TaskQueue.QueueStats active = TaskQueue.QueueStats.knownAt(OBSERVED_AT, 2, 0, 2, 0, 0);
        TaskQueue.QueueStats backlog = TaskQueue.QueueStats.knownAt(OBSERVED_AT, 3, 3, 0, 0, 0);
        TaskQueue.QueueStats stale = TaskQueue.QueueStats.knownAt(OBSERVED_AT, 4, 2, 0, 2, 0);
        TaskQueue.QueueStats unreadable = TaskQueue.QueueStats.knownAt(OBSERVED_AT, 5, 2, 0, 2, 1);

        assertTrue(idle.known());
        assertEquals(OBSERVED_AT, idle.observedAt());
        assertEquals(TaskQueue.QueueHealth.IDLE, idle.health());
        assertEquals(TaskQueue.QueueHealth.ACTIVE, active.health());
        assertEquals(TaskQueue.QueueHealth.BACKLOG, backlog.health());
        assertEquals(TaskQueue.QueueHealth.STALE_LEASES, stale.health());
        assertEquals(TaskQueue.QueueHealth.UNREADABLE_RECORDS, unreadable.health());
        assertEquals(4, stale.claimable());
        assertEquals(4, unreadable.claimable());
    }

    @Test
    void unknownStatsUseSentinelCountsAndUnknownHealth() {
        TaskQueue.QueueStats stats = TaskQueue.QueueStats.unknownAt(OBSERVED_AT);

        assertFalse(stats.known());
        assertEquals(OBSERVED_AT, stats.observedAt());
        assertEquals(TaskQueue.QueueHealth.UNKNOWN, stats.health());
        assertEquals(-1, stats.total());
        assertEquals(-1, stats.available());
        assertEquals(-1, stats.leased());
        assertEquals(-1, stats.expired());
        assertEquals(-1, stats.unreadable());
        assertEquals(-1, stats.claimable());
    }

    @Test
    void knownStatsRejectNegativeCounts() {
        assertThrows(IllegalArgumentException.class,
                () -> TaskQueue.QueueStats.knownAt(OBSERVED_AT, -1, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> TaskQueue.QueueStats.knownAt(OBSERVED_AT, 0, -1, 0, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> TaskQueue.QueueStats.knownAt(OBSERVED_AT, 0, 0, -1, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> TaskQueue.QueueStats.knownAt(OBSERVED_AT, 0, 0, 0, -1, 0));
        assertThrows(IllegalArgumentException.class,
                () -> TaskQueue.QueueStats.knownAt(OBSERVED_AT, 0, 0, 0, 0, -1));
    }
}
