package tech.kayys.gamelan.registry.repository;

import io.quarkus.hibernate.reactive.panache.PanacheRepository;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import tech.kayys.gamelan.registry.entity.ExecutorEntity;

import java.util.List;

@ApplicationScoped
public class ExecutorJpaRepositoryImpl implements ExecutorJpaRepository, PanacheRepository<ExecutorEntity> {

    @Override
    public Uni<Void> save(ExecutorEntity executor) {
        return persist(executor).replaceWithVoid();
    }

    @Override
    public Uni<ExecutorEntity> findById(String executorId) {
        // Reactive findById returns Uni<Entity>
        // Use find().firstResult() if needed, or findById(id)
        // Since ID is String, and @Id is String, findById should work if Panache
        // supports it.
        // If not, find("executorId", id).firstResult()
        return find("executorId", executorId).firstResult();
    }

    @Override
    public Uni<List<ExecutorEntity>> getAllExecutors() {
        return findAll().list();
    }

    @Override
    public Uni<Void> deleteById(String executorId) {
        return delete("executorId", executorId).replaceWithVoid();
    }

    @Override
    public Uni<List<ExecutorEntity>> findByExecutorType(String executorType) {
        return find("executorType", executorType).list();
    }

    @Override
    public Uni<List<ExecutorEntity>> findByCommunicationType(String communicationType) {
        return find("communicationType", communicationType).list();
    }
}