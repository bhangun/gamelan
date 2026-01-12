package tech.kayys.silat.distributed;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.value.ValueCommands;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class RedisDistributedLockManager implements DistributedLockManager {

    private static final Logger LOG = LoggerFactory.getLogger(RedisDistributedLockManager.class);
    private static final String LOCK_PREFIX = "workflow:lock:";

    @Inject
    RedisDataSource redis;

    @Override
    public Uni<DistributedLock> acquireLock(String lockKey, Duration timeout) {
        String fullKey = LOCK_PREFIX + lockKey;
        String lockValue = UUID.randomUUID().toString();

        return Uni.createFrom().deferred(() -> {
            Instant deadline = Instant.now().plus(timeout);
            return tryAcquire(fullKey, lockValue, deadline);
        });
    }

    private Uni<DistributedLock> tryAcquire(String key, String value, Instant deadline) {
        ValueCommands<String, String> commands = redis.value(String.class);

        // Try to acquire lock with NX (set if not exists)
        boolean acquired = commands.setnx(key, value);

        if (acquired) {
            // Set expiration to prevent deadlocks
            redis.key().expire(key, Duration.ofSeconds(30));

            LOG.debug("Lock acquired: {}", key);
            return Uni.createFrom().item(new DistributedLock(key, value, Instant.now()));
        }

        // Check if timeout exceeded
        if (Instant.now().isAfter(deadline)) {
            return Uni.createFrom().failure(
                    new TimeoutException("Failed to acquire lock: " + key));
        }

        // Retry after short delay
        return Uni.createFrom().voidItem()
                .onItem().delayIt().by(Duration.ofMillis(100))
                .flatMap(v -> tryAcquire(key, value, deadline));
    }

    @Override
    public Uni<Void> releaseLock(DistributedLock lock) {
        ValueCommands<String, String> commands = redis.value(String.class);

        // Only delete if we own the lock (check value matches)
        String currentValue = commands.get(lock.key());
        if (lock.value().equals(currentValue)) {
            commands.getdel(lock.key());
            LOG.debug("Lock released: {}", lock.key());
        }

        return Uni.createFrom().voidItem();
    }

    @Override
    public Uni<Boolean> isLocked(String lockKey) {
        String fullKey = LOCK_PREFIX + lockKey;
        String value = redis.value(String.class).get(fullKey);
        return Uni.createFrom().item(value != null);
    }
}
