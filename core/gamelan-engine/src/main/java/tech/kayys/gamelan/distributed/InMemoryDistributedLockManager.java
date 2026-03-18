package tech.kayys.gamelan.distributed;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.enterprise.context.ApplicationScoped;
import io.smallrye.mutiny.Uni;
import io.quarkus.arc.properties.IfBuildProperty;

@ApplicationScoped
@IfBuildProperty(name = "gamelan.distributed.mode", stringValue = "local", stringValueIfMissing = "local")
public class InMemoryDistributedLockManager implements DistributedLockManager {

    private final Map<String, String> locks = new ConcurrentHashMap<>();

    @Override
    public Uni<DistributedLock> acquireLock(String lockKey, Duration timeout) {
        String value = java.util.UUID.randomUUID().toString();
        if (locks.putIfAbsent(lockKey, value) == null) {
            return Uni.createFrom().item(new DistributedLock(lockKey, value, Instant.now()));
        }
        // Simplified: standalone usually doesn't need complex distributed locking
        // but for compatibility we could implement a simple wait/retry if needed.
        return Uni.createFrom().failure(new RuntimeException("Lock already held: " + lockKey));
    }

    @Override
    public Uni<Void> releaseLock(DistributedLock lock) {
        locks.remove(lock.key(), lock.value());
        return Uni.createFrom().voidItem();
    }

    @Override
    public Uni<Boolean> isLocked(String lockKey) {
        return Uni.createFrom().item(locks.containsKey(lockKey));
    }
}
