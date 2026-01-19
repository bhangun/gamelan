package tech.kayys.gamelan.distributed;

import java.time.Instant;

/**
 * Distributed Lock
 */
public record DistributedLock(
        String key,
        String value,
        Instant acquiredAt) {
}
