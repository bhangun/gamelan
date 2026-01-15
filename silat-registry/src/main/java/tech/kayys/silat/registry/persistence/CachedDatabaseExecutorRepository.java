package tech.kayys.silat.registry.persistence;

import io.quarkus.arc.properties.IfBuildProperty;
import io.quarkus.cache.CacheInvalidate;
import io.quarkus.cache.CacheInvalidateAll;
import io.quarkus.cache.CacheResult;
import io.quarkus.cache.CacheKey;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;
import tech.kayys.silat.model.CommunicationType;
import tech.kayys.silat.model.ExecutorInfo;
import tech.kayys.silat.registry.repository.ExecutorJpaRepositoryImpl;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Multi-layer cached database implementation of ExecutorRepository
 * Uses in-memory (Caffeine) + distributed (Redis) caching with reactive
 * database as fallback
 */
@ApplicationScoped
@IfBuildProperty(name = "silat.registry.persistence.type", stringValue = "cached-database")
public class CachedDatabaseExecutorRepository implements ExecutorRepository {

    private static final Logger LOG = Logger.getLogger(CachedDatabaseExecutorRepository.class);

    @Inject
    ExecutorJpaRepositoryImpl executorJpaRepository;

    @Override
    public Uni<Void> save(ExecutorInfo executor) {
        if (executor == null) {
            LOG.warn("Attempted to save null executor");
            return Uni.createFrom().failure(new IllegalArgumentException("Executor cannot be null"));
        }

        if (executor.executorId() == null || executor.executorId().trim().isEmpty()) {
            LOG.warn("Attempted to save executor with null or empty ID");
            return Uni.createFrom().failure(new IllegalArgumentException("Executor ID cannot be null or empty"));
        }

        LOG.debugf("Saving executor with ID: %s", executor.executorId());

        var executorEntity = new tech.kayys.silat.registry.entity.ExecutorEntity(executor);

        // Invalidate cache for this executor and related caches
        return invalidateExecutorCaches(executor.executorId(), executor.executorType(), executor.communicationType())
                .chain(() -> executorJpaRepository.save(executorEntity))
                .onItem().invoke(() -> LOG.infof("Successfully saved executor with ID: %s", executor.executorId()))
                .onFailure()
                .invoke(failure -> LOG.errorf(failure, "Failed to save executor with ID: %s", executor.executorId()))
                .replaceWithVoid();
    }

    @Override
    @CacheResult(cacheName = "executors")
    public Uni<Optional<ExecutorInfo>> findById(String executorId) {
        if (executorId == null || executorId.trim().isEmpty()) {
            LOG.warn("Attempted to find executor with null or empty ID");
            return Uni.createFrom().item(Optional.empty());
        }

        LOG.debugf("Finding executor with ID: %s", executorId);

        return executorJpaRepository.findById(executorId)
                .onItem().invoke(entity -> {
                    if (entity != null) {
                        LOG.debugf("Found executor with ID: %s in database", executorId);
                    } else {
                        LOG.debugf("Executor with ID %s not found in database", executorId);
                    }
                })
                .onFailure().invoke(failure -> LOG.errorf(failure, "Error finding executor with ID: %s", executorId))
                .map(entity -> entity != null ? Optional.of(entity.toExecutorInfo()) : Optional.empty());
    }

    @Override
    @CacheResult(cacheName = "executors")
    public Uni<List<ExecutorInfo>> findAll() {
        LOG.debug("Retrieving all executors from database");
        return executorJpaRepository.getAllExecutors()
                .onItem().invoke(list -> LOG.infof("Retrieved %d executors from database", list.size()))
                .onFailure().invoke(failure -> LOG.errorf(failure, "Error retrieving all executors"))
                .map(entities -> entities.stream()
                        .map(tech.kayys.silat.registry.entity.ExecutorEntity::toExecutorInfo)
                        .collect(Collectors.toList()));
    }

    @Override
    public Uni<Void> delete(String executorId) {
        if (executorId == null || executorId.trim().isEmpty()) {
            LOG.warn("Attempted to delete executor with null or empty ID");
            return Uni.createFrom().voidItem(); // Idempotent operation
        }

        LOG.debugf("Deleting executor with ID: %s", executorId);

        // First get the executor to know its type and communication type for cache
        // invalidation
        return executorJpaRepository.findById(executorId)
                .flatMap(executorEntity -> {
                    if (executorEntity != null) {
                        // Invalidate cache for this executor and related caches
                        return invalidateExecutorCaches(executorId, executorEntity.getExecutorType(),
                                executorEntity.getCommunicationType())
                                .chain(() -> executorJpaRepository.deleteById(executorId));
                    } else {
                        return executorJpaRepository.deleteById(executorId); // Just delete from DB if not found
                    }
                })
                .onItem().invoke(() -> LOG.infof("Successfully deleted executor with ID: %s", executorId))
                .onFailure().invoke(failure -> LOG.errorf(failure, "Failed to delete executor with ID: %s", executorId))
                .replaceWithVoid();
    }

    @Override
    @CacheResult(cacheName = "executors-by-type")
    public Uni<List<ExecutorInfo>> findByType(String executorType) {
        if (executorType == null || executorType.trim().isEmpty()) {
            LOG.warn("Attempted to find executors with null or empty type");
            return Uni.createFrom().item(List.of());
        }

        LOG.debugf("Finding executors by type: %s", executorType);
        return executorJpaRepository.findByExecutorType(executorType)
                .onItem().invoke(list -> LOG.infof("Found %d executors of type: %s", list.size(), executorType))
                .onFailure().invoke(failure -> LOG.errorf(failure, "Error finding executors by type: %s", executorType))
                .map(entities -> entities.stream()
                        .map(tech.kayys.silat.registry.entity.ExecutorEntity::toExecutorInfo)
                        .collect(Collectors.toList()));
    }

    @Override
    @CacheResult(cacheName = "executors-by-communication-type")
    public Uni<List<ExecutorInfo>> findByCommunicationType(CommunicationType communicationType) {
        if (communicationType == null) {
            LOG.warn("Attempted to find executors with null communication type");
            return Uni.createFrom().item(List.of());
        }

        LOG.debugf("Finding executors by communication type: %s", communicationType);
        return executorJpaRepository.findByCommunicationType(communicationType.toString())
                .onItem()
                .invoke(list -> LOG.infof("Found %d executors with communication type: %s", list.size(),
                        communicationType))
                .onFailure()
                .invoke(failure -> LOG.errorf(failure, "Error finding executors by communication type: %s",
                        communicationType))
                .map(entities -> entities.stream()
                        .map(tech.kayys.silat.registry.entity.ExecutorEntity::toExecutorInfo)
                        .collect(Collectors.toList()));
    }

    /**
     * Helper method to invalidate all related caches for an executor
     */
    private Uni<Void> invalidateExecutorCaches(String executorId, String executorType,
            CommunicationType communicationType) {
        return Uni.combine().all()
                .unis(
                        invalidateExecutorById(executorId),
                        invalidateAllExecutors(),
                        invalidateExecutorsByType(executorType),
                        invalidateExecutorsByCommunicationType(communicationType))
                .discardItems();
    }

    /**
     * Cache invalidation methods
     */
    @CacheInvalidate(cacheName = "executors")
    public Uni<Void> invalidateExecutorById(@CacheKey String executorId) {
        LOG.debugf("Invalidating cache for executor ID: %s", executorId);
        return Uni.createFrom().voidItem();
    }

    @CacheInvalidateAll(cacheName = "executors")
    public Uni<Void> invalidateAllExecutors() {
        LOG.debug("Invalidating all executors cache");
        return Uni.createFrom().voidItem();
    }

    @CacheInvalidate(cacheName = "executors-by-type")
    public Uni<Void> invalidateExecutorsByType(@CacheKey String executorType) {
        LOG.debugf("Invalidating cache for executors by type: %s", executorType);
        return Uni.createFrom().voidItem();
    }

    @CacheInvalidate(cacheName = "executors-by-communication-type")
    public Uni<Void> invalidateExecutorsByCommunicationType(@CacheKey CommunicationType communicationType) {
        LOG.debugf("Invalidating cache for executors by communication type: %s", communicationType);
        return Uni.createFrom().voidItem();
    }
}