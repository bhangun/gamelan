package tech.kayys.silat.registry.persistence;

import io.quarkus.arc.properties.IfBuildProperty;
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
 * Production-ready database implementation of ExecutorRepository
 * Uses JPA/Hibernate with Panache for database operations
 */
@ApplicationScoped
@IfBuildProperty(name = "silat.registry.persistence.type", stringValue = "database")
public class DatabaseExecutorRepository implements ExecutorRepository {

    private static final Logger LOG = Logger.getLogger(DatabaseExecutorRepository.class);

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
        return executorJpaRepository.save(executorEntity)
                .onItem().invoke(() -> LOG.infof("Successfully saved executor with ID: %s", executor.executorId()))
                .onFailure()
                .invoke(failure -> LOG.errorf(failure, "Failed to save executor with ID: %s", executor.executorId()))
                .replaceWithVoid();
    }

    @Override
    public Uni<Optional<ExecutorInfo>> findById(String executorId) {
        if (executorId == null || executorId.trim().isEmpty()) {
            LOG.warn("Attempted to find executor with null or empty ID");
            return Uni.createFrom().item(Optional.empty());
        }

        LOG.debugf("Finding executor with ID: %s", executorId);
        return executorJpaRepository.findById(executorId)
                .onItem().invoke(entity -> {
                    if (entity != null) {
                        LOG.debugf("Found executor with ID: %s", executorId);
                    } else {
                        LOG.debugf("Executor with ID %s not found", executorId);
                    }
                })
                .onFailure().invoke(failure -> LOG.errorf(failure, "Error finding executor with ID: %s", executorId))
                .map(entity -> entity != null ? Optional.of(entity.toExecutorInfo()) : Optional.empty());
    }

    @Override
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
        return executorJpaRepository.deleteById(executorId)
                .onItem().invoke(() -> LOG.infof("Successfully deleted executor with ID: %s", executorId))
                .onFailure().invoke(failure -> LOG.errorf(failure, "Failed to delete executor with ID: %s", executorId))
                .replaceWithVoid();
    }

    @Override
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
}