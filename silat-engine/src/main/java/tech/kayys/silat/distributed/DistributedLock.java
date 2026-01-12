package tech.kayys.silat.distributed;

import java.time.Instant;

/**
 * Distributed Lock
 */
public record DistributedLock(
        String key,
        String value,
        Instant acquiredAt) {
}
