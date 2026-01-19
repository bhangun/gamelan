package tech.kayys.gamelan.registry.persistence;

import java.util.List;
import java.util.Optional;

import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.engine.executor.ExecutorInfo;

/**
 * Repository for persistent storage of executor information
 */
public interface ExecutorRepository {
    
    /**
     * Save an executor
     */
    Uni<Void> save(ExecutorInfo executor);
    
    /**
     * Find an executor by ID
     */
    Uni<Optional<ExecutorInfo>> findById(String executorId);
    
    /**
     * Find all executors
     */
    Uni<List<ExecutorInfo>> findAll();
    
    /**
     * Delete an executor
     */
    Uni<Void> delete(String executorId);
    
    /**
     * Find executors by type
     */
    Uni<List<ExecutorInfo>> findByType(String executorType);
    
    /**
     * Find executors by communication type
     */
    Uni<List<ExecutorInfo>> findByCommunicationType(tech.kayys.gamelan.engine.protocol.CommunicationType communicationType);
}