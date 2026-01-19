package tech.kayys.gamelan.registry.repository;

import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.registry.entity.ExecutorEntity;

import java.util.List;

public interface ExecutorJpaRepository {
    Uni<Void> save(ExecutorEntity executor);

    Uni<ExecutorEntity> findById(String executorId);

    Uni<List<ExecutorEntity>> getAllExecutors();

    Uni<Void> deleteById(String executorId);

    Uni<List<ExecutorEntity>> findByExecutorType(String executorType);

    Uni<List<ExecutorEntity>> findByCommunicationType(String communicationType);
}