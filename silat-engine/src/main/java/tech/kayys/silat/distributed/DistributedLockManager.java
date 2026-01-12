package tech.kayys.silat.distributed;

import java.time.Duration;

import io.smallrye.mutiny.Uni;

/**
 * Distributed Lock Manager using Redis
 */
public interface DistributedLockManager {

    Uni<DistributedLock> acquireLock(String lockKey, Duration timeout);

    Uni<Void> releaseLock(DistributedLock lock);

    Uni<Boolean> isLocked(String lockKey);
}
