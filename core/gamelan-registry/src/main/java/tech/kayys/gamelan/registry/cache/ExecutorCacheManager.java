package tech.kayys.gamelan.registry.cache;

import io.quarkus.cache.CacheResult;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import tech.kayys.gamelan.engine.protocol.CommunicationType;
import tech.kayys.gamelan.engine.executor.ExecutorInfo;

import java.util.List;
import java.util.Optional;

/**
 * Multi-layer cache service for executor information
 * Combines in-memory (Caffeine) and distributed (Redis) caching
 */
@ApplicationScoped
public class ExecutorCacheManager {

    private static final Logger LOG = Logger.getLogger(ExecutorCacheManager.class);

    @Inject
    io.quarkus.redis.datasource.ReactiveRedisDataSource redisDataSource;

    /**
     * Get executor by ID with multi-layer caching
     */
    @CacheResult(cacheName = "executors")
    public Uni<Optional<ExecutorInfo>> getExecutorById(String executorId) {
        LOG.debugf("Cache miss for executor ID: %s", executorId);
        // This method will be called when cache misses, and the result will be cached
        return Uni.createFrom().item(Optional.empty()); // Placeholder - actual DB call happens in repository
    }

    /**
     * Get all executors with caching
     */
    @CacheResult(cacheName = "executors")
    public Uni<List<ExecutorInfo>> getAllExecutors() {
        LOG.debug("Cache miss for all executors");
        // This method will be called when cache misses
        return Uni.createFrom().item(List.of()); // Placeholder - actual DB call happens in repository
    }

    /**
     * Get executors by type with caching
     */
    @CacheResult(cacheName = "executors-by-type")
    public Uni<List<ExecutorInfo>> getExecutorsByType(String executorType) {
        LOG.debugf("Cache miss for executors by type: %s", executorType);
        return Uni.createFrom().item(List.of()); // Placeholder - actual DB call happens in repository
    }

    /**
     * Get executors by communication type with caching
     */
    @CacheResult(cacheName = "executors-by-communication-type")
    public Uni<List<ExecutorInfo>> getExecutorsByCommunicationType(CommunicationType communicationType) {
        LOG.debugf("Cache miss for executors by communication type: %s", communicationType);
        return Uni.createFrom().item(List.of()); // Placeholder - actual DB call happens in repository
    }

    /**
     * Invalidate executor cache by ID
     */
    public Uni<Void> invalidateExecutorById(String executorId) {
        LOG.infof("Invalidating cache for executor ID: %s", executorId);
        // In a real implementation, we would also invalidate the Redis cache here
        return Uni.createFrom().voidItem();
    }

    /**
     * Invalidate all executors cache
     */
    public Uni<Void> invalidateAllExecutors() {
        LOG.info("Invalidating all executors cache");
        // In a real implementation, we would also invalidate the Redis cache here
        return Uni.createFrom().voidItem();
    }

    /**
     * Invalidate executors by type cache
     */
    public Uni<Void> invalidateExecutorsByType(String executorType) {
        LOG.infof("Invalidating cache for executors by type: %s", executorType);
        // In a real implementation, we would also invalidate the Redis cache here
        return Uni.createFrom().voidItem();
    }

    /**
     * Invalidate executors by communication type cache
     */
    public Uni<Void> invalidateExecutorsByCommunicationType(CommunicationType communicationType) {
        LOG.infof("Invalidating cache for executors by communication type: %s", communicationType);
        // In a real implementation, we would also invalidate the Redis cache here
        return Uni.createFrom().voidItem();
    }
}