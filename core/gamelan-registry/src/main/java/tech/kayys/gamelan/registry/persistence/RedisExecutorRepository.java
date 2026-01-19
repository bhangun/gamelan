package tech.kayys.gamelan.registry.persistence;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import io.quarkus.arc.properties.IfBuildProperty;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import tech.kayys.gamelan.engine.protocol.CommunicationType;
import tech.kayys.gamelan.engine.executor.ExecutorInfo;

/**
 * Production-ready Redis-based implementation of ExecutorRepository
 * Implements proper indexing and querying capabilities
 */
@ApplicationScoped
@IfBuildProperty(name = "gamelan.registry.persistence.type", stringValue = "redis")
public class RedisExecutorRepository implements ExecutorRepository {

    private static final Logger LOG = Logger.getLogger(RedisExecutorRepository.class);

    // Key prefixes for different data structures
    private static final String EXECUTOR_KEY_PREFIX = "executor:";
    private static final String ALL_EXECUTORS_SET = "executors:all";
    private static final String TYPE_INDEX_PREFIX = "executors:type:";
    private static final String COMM_TYPE_INDEX_PREFIX = "executors:comm_type:";

    @Inject
    ReactiveRedisDataSource redis;

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

        return Uni.createFrom().deferred(() -> {
            // Serialize executor to JSON
            String executorJson;
            try {
                executorJson = JsonObject.mapFrom(executor).encode();
            } catch (Exception e) {
                LOG.errorf(e, "Failed to serialize executor: %s", executor.executorId());
                return Uni.createFrom().failure(e);
            }

            String executorKey = EXECUTOR_KEY_PREFIX + executor.executorId();

            // Pipeline operations: save executor and update indexes
            return redis.value(String.class, String.class).set(executorKey, executorJson)
                    .onItem().call(() -> addToIndex(ALL_EXECUTORS_SET, executor.executorId()))
                    .onItem().call(() -> addToIndex(TYPE_INDEX_PREFIX + executor.executorType(), executor.executorId()))
                    .onItem()
                    .call(() -> addToIndex(COMM_TYPE_INDEX_PREFIX + executor.communicationType().toString(),
                            executor.executorId()))
                    .onItem().invoke(() -> LOG.infof("Successfully saved executor with ID: %s", executor.executorId()))
                    .onFailure()
                    .invoke(failure -> LOG.errorf(failure, "Failed to save executor with ID: %s",
                            executor.executorId()))
                    .replaceWithVoid();
        });
    }

    @Override
    public Uni<Optional<ExecutorInfo>> findById(String executorId) {
        if (executorId == null || executorId.trim().isEmpty()) {
            LOG.warn("Attempted to find executor with null or empty ID");
            return Uni.createFrom().item(Optional.empty());
        }

        LOG.debugf("Finding executor with ID: %s", executorId);

        return Uni.createFrom().deferred(() -> {
            String executorKey = EXECUTOR_KEY_PREFIX + executorId;

            return redis.value(String.class, String.class).get(executorKey)
                    .onItem().invoke(jsonStr -> {
                        if (jsonStr != null) {
                            LOG.debugf("Found executor with ID: %s in Redis", executorId);
                        } else {
                            LOG.debugf("Executor with ID %s not found in Redis", executorId);
                        }
                    })
                    .onFailure()
                    .invoke(failure -> LOG.errorf(failure, "Error finding executor with ID: %s", executorId))
                    .map(jsonStr -> {
                        if (jsonStr == null) {
                            return Optional.empty();
                        }
                        try {
                            ExecutorInfo executor = new JsonObject(jsonStr).mapTo(ExecutorInfo.class);
                            return Optional.of(executor);
                        } catch (Exception e) {
                            LOG.errorf(e, "Error deserializing executor: %s", executorId);
                            return Optional.empty();
                        }
                    });
        });
    }

    @Override
    public Uni<List<ExecutorInfo>> findAll() {
        LOG.debug("Retrieving all executors from Redis");

        return Uni.createFrom().deferred(() -> {
            // Get all executor IDs from the main index
            return redis.sortedSet(String.class, String.class).zrange(ALL_EXECUTORS_SET, 0, -1)
                    .onItem().invoke(ids -> LOG.debugf("Found %d executor IDs in main index", ids.size()))
                    .onFailure().invoke(failure -> LOG.errorf(failure, "Error retrieving executor IDs from main index"))
                    .flatMap(ids -> {
                        if (ids.isEmpty()) {
                            LOG.info("No executors found in Redis");
                            return Uni.createFrom().item(List.<ExecutorInfo>of());
                        }

                        // Get all executor details using MGET for efficiency
                        String[] executorKeys = ids.stream()
                                .map(id -> EXECUTOR_KEY_PREFIX + id)
                                .toArray(String[]::new);

                        return redis.value(String.class, String.class).mget(executorKeys)
                                .map(executorMap -> {
                                    return executorMap.values().stream()
                                            .filter(json -> json != null && !json.isEmpty())
                                            .map(this::deserializeExecutor)
                                            .filter(Optional::isPresent)
                                            .map(Optional::get)
                                            .collect(Collectors.toList());
                                });
                    })
                    .onItem().invoke(executors -> LOG.infof("Retrieved %d executors from Redis", executors.size()));
        });
    }

    @Override
    public Uni<Void> delete(String executorId) {
        if (executorId == null || executorId.trim().isEmpty()) {
            LOG.warn("Attempted to delete executor with null or empty ID");
            return Uni.createFrom().voidItem(); // Idempotent operation
        }

        LOG.debugf("Deleting executor with ID: %s", executorId);

        return Uni.createFrom().deferred(() -> {
            String executorKey = EXECUTOR_KEY_PREFIX + executorId;

            // First, get the executor to determine which indexes to update
            return redis.value(String.class, String.class).get(executorKey)
                    .flatMap(executorJson -> {
                        if (executorJson == null) {
                            LOG.warnf("Executor with ID %s not found for deletion", executorId);
                            return Uni.createFrom().voidItem();
                        }

                        // Deserialize to get type and communication type for index removal
                        Optional<ExecutorInfo> executorOpt = deserializeExecutor(executorJson);
                        if (!executorOpt.isPresent()) {
                            LOG.warnf("Could not deserialize executor %s for index cleanup", executorId);
                            // Still delete the main key even if deserialization failed
                            return redis.key().del(executorKey).replaceWithVoid();
                        }

                        ExecutorInfo executor = executorOpt.get();

                        // Remove from all indexes and delete the main key
                        return removeFromIndex(ALL_EXECUTORS_SET, executorId)
                                .chain(() -> removeFromIndex(TYPE_INDEX_PREFIX + executor.executorType(), executorId))
                                .chain(() -> removeFromIndex(
                                        COMM_TYPE_INDEX_PREFIX + executor.communicationType().toString(), executorId))
                                .chain(() -> redis.key().del(executorKey))
                                .onItem()
                                .invoke(() -> LOG.infof("Successfully deleted executor with ID: %s", executorId))
                                .onFailure()
                                .invoke(failure -> LOG.errorf(failure, "Failed to delete executor with ID: %s",
                                        executorId))
                                .replaceWithVoid();
                    });
        });
    }

    @Override
    public Uni<List<ExecutorInfo>> findByType(String executorType) {
        if (executorType == null || executorType.trim().isEmpty()) {
            LOG.warn("Attempted to find executors with null or empty type");
            return Uni.createFrom().item(List.<ExecutorInfo>of());
        }

        LOG.debugf("Finding executors by type: %s", executorType);

        return Uni.createFrom().deferred(() -> {
            String typeIndexKey = TYPE_INDEX_PREFIX + executorType;

            return redis.sortedSet(String.class, String.class).zrange(typeIndexKey, 0, -1)
                    .onItem().invoke(ids -> LOG.debugf("Found %d executors of type %s", ids.size(), executorType))
                    .onFailure()
                    .invoke(failure -> LOG.errorf(failure, "Error finding executors by type: %s", executorType))
                    .flatMap(ids -> {
                        if (ids.isEmpty()) {
                            LOG.infof("No executors found for type: %s", executorType);
                            return Uni.createFrom().item(List.<ExecutorInfo>of());
                        }

                        // Get all executor details using MGET for efficiency
                        String[] executorKeys = ids.stream()
                                .map(id -> EXECUTOR_KEY_PREFIX + id)
                                .toArray(String[]::new);

                        return redis.value(String.class, String.class).mget(executorKeys)
                                .map(executorMap -> {
                                    return executorMap.values().stream()
                                            .filter(json -> json != null && !json.isEmpty())
                                            .map(this::deserializeExecutor)
                                            .filter(Optional::isPresent)
                                            .map(Optional::get)
                                            .collect(Collectors.toList());
                                });
                    })
                    .onItem()
                    .invoke(executors -> LOG.infof("Found %d executors of type: %s", executors.size(), executorType));
        });
    }

    @Override
    public Uni<List<ExecutorInfo>> findByCommunicationType(CommunicationType communicationType) {
        if (communicationType == null) {
            LOG.warn("Attempted to find executors with null communication type");
            return Uni.createFrom().item(List.<ExecutorInfo>of());
        }

        LOG.debugf("Finding executors by communication type: %s", communicationType);

        return Uni.createFrom().deferred(() -> {
            String commTypeIndexKey = COMM_TYPE_INDEX_PREFIX + communicationType.toString();

            return redis.sortedSet(String.class, String.class).zrange(commTypeIndexKey, 0, -1)
                    .onItem()
                    .invoke(ids -> LOG.debugf("Found %d executors with communication type %s", ids.size(),
                            communicationType))
                    .onFailure()
                    .invoke(failure -> LOG.errorf(failure, "Error finding executors by communication type: %s",
                            communicationType))
                    .flatMap(ids -> {
                        if (ids.isEmpty()) {
                            LOG.infof("No executors found for communication type: %s", communicationType);
                            return Uni.createFrom().item(List.<ExecutorInfo>of());
                        }

                        // Get all executor details using MGET for efficiency
                        String[] executorKeys = ids.stream()
                                .map(id -> EXECUTOR_KEY_PREFIX + id)
                                .toArray(String[]::new);

                        return redis.value(String.class, String.class).mget(executorKeys)
                                .map(executorMap -> {
                                    return executorMap.values().stream()
                                            .filter(json -> json != null && !json.isEmpty())
                                            .map(this::deserializeExecutor)
                                            .filter(Optional::isPresent)
                                            .map(Optional::get)
                                            .collect(Collectors.toList());
                                });
                    })
                    .onItem().invoke(executors -> LOG.infof("Found %d executors with communication type: %s",
                            executors.size(), communicationType));
        });
    }

    /**
     * Helper method to add an executor ID to an index
     */
    private Uni<Void> addToIndex(String indexKey, String executorId) {
        return redis.sortedSet(String.class, String.class)
                .zadd(indexKey, (double) System.currentTimeMillis(), executorId)
                .onFailure()
                .invoke(failure -> LOG.errorf(failure, "Failed to add executor %s to index %s", executorId, indexKey))
                .replaceWithVoid();
    }

    /**
     * Helper method to remove an executor ID from an index
     */
    private Uni<Void> removeFromIndex(String indexKey, String executorId) {
        return redis.sortedSet(String.class, String.class).zrem(indexKey, executorId)
                .onFailure()
                .invoke(failure -> LOG.errorf(failure, "Failed to remove executor %s from index %s", executorId,
                        indexKey))
                .replaceWithVoid();
    }

    /**
     * Helper method to deserialize executor from JSON string
     */
    private Optional<ExecutorInfo> deserializeExecutor(String jsonStr) {
        if (jsonStr == null || jsonStr.isEmpty()) {
            return Optional.empty();
        }
        try {
            ExecutorInfo executor = new JsonObject(jsonStr).mapTo(ExecutorInfo.class);
            return Optional.of(executor);
        } catch (Exception e) {
            LOG.errorf(e, "Error deserializing executor from JSON: %s", jsonStr);
            return Optional.empty();
        }
    }
}