package tech.kayys.gamelan.registry.persistence;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import io.quarkus.arc.properties.IfBuildProperty;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;
import tech.kayys.gamelan.engine.protocol.CommunicationType;
import tech.kayys.gamelan.engine.executor.ExecutorInfo;

/**
 * Improved in-memory implementation of ExecutorRepository with metrics
 * In production, this would be replaced with a database implementation
 */
@ApplicationScoped
@IfBuildProperty(name = "gamelan.registry.persistence.type", stringValue = "memory")
public class InMemoryExecutorRepository implements ExecutorRepository {

    private static final Logger LOG = Logger.getLogger(InMemoryExecutorRepository.class);

    private final Map<String, ExecutorInfo> executors = new ConcurrentHashMap<>();

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
        executors.put(executor.executorId(), executor);
        LOG.infof("Successfully saved executor with ID: %s", executor.executorId());
        return Uni.createFrom().voidItem();
    }

    @Override
    public Uni<Optional<ExecutorInfo>> findById(String executorId) {
        if (executorId == null || executorId.trim().isEmpty()) {
            LOG.warn("Attempted to find executor with null or empty ID");
            return Uni.createFrom().item(Optional.empty());
        }

        LOG.debugf("Finding executor with ID: %s", executorId);
        ExecutorInfo executor = executors.get(executorId);
        if (executor != null) {
            LOG.debugf("Found executor with ID: %s", executorId);
        } else {
            LOG.debugf("Executor with ID %s not found", executorId);
        }
        return Uni.createFrom().item(Optional.ofNullable(executor));
    }

    @Override
    public Uni<List<ExecutorInfo>> findAll() {
        LOG.debug("Retrieving all executors");
        List<ExecutorInfo> allExecutors = List.copyOf(executors.values());
        LOG.infof("Retrieved %d executors", allExecutors.size());
        return Uni.createFrom().item(allExecutors);
    }

    @Override
    public Uni<Void> delete(String executorId) {
        if (executorId == null || executorId.trim().isEmpty()) {
            LOG.warn("Attempted to delete executor with null or empty ID");
            return Uni.createFrom().voidItem(); // Idempotent operation
        }

        LOG.debugf("Deleting executor with ID: %s", executorId);
        ExecutorInfo removed = executors.remove(executorId);
        if (removed != null) {
            LOG.infof("Successfully deleted executor with ID: %s", executorId);
        } else {
            LOG.warnf("Attempted to delete non-existent executor with ID: %s", executorId);
        }
        return Uni.createFrom().voidItem();
    }

    @Override
    public Uni<List<ExecutorInfo>> findByType(String executorType) {
        if (executorType == null || executorType.trim().isEmpty()) {
            LOG.warn("Attempted to find executors with null or empty type");
            return Uni.createFrom().item(List.of());
        }

        LOG.debugf("Finding executors by type: %s", executorType);
        List<ExecutorInfo> filtered = executors.values().stream()
                .filter(executor -> executor.executorType() != null && executor.executorType().equals(executorType))
                .collect(Collectors.toList());
        LOG.infof("Found %d executors of type: %s", filtered.size(), executorType);
        return Uni.createFrom().item(filtered);
    }

    @Override
    public Uni<List<ExecutorInfo>> findByCommunicationType(CommunicationType communicationType) {
        if (communicationType == null) {
            LOG.warn("Attempted to find executors with null communication type");
            return Uni.createFrom().item(List.of());
        }

        LOG.debugf("Finding executors by communication type: %s", communicationType);
        List<ExecutorInfo> filtered = executors.values().stream()
                .filter(executor -> executor.communicationType() != null
                        && executor.communicationType() == communicationType)
                .collect(Collectors.toList());
        LOG.infof("Found %d executors with communication type: %s", filtered.size(), communicationType);
        return Uni.createFrom().item(filtered);
    }
}