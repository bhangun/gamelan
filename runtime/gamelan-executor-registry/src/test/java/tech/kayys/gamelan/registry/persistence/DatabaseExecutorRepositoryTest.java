package tech.kayys.gamelan.registry.persistence;

import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tech.kayys.gamelan.engine.protocol.CommunicationType;
import tech.kayys.gamelan.engine.executor.ExecutorInfo;
import tech.kayys.gamelan.registry.repository.ExecutorJpaRepositoryImpl;
import tech.kayys.gamelan.registry.entity.ExecutorEntity;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseExecutorRepositoryTest {

    private FakeExecutorJpaRepository executorJpaRepository;
    private DatabaseExecutorRepository databaseExecutorRepository;

    @BeforeEach
    void setUp() {
        executorJpaRepository = new FakeExecutorJpaRepository();
        databaseExecutorRepository = new DatabaseExecutorRepository();
        databaseExecutorRepository.executorJpaRepository = executorJpaRepository;
    }

    @Test
    void save_ValidExecutor_ShouldSaveSuccessfully() {
        // Arrange
        ExecutorInfo executor = new ExecutorInfo(
                "executor-1",
                "test-type",
                CommunicationType.GRPC,
                "http://localhost:8080",
                Duration.ofSeconds(30),
                Map.of("key1", "value1"));

        // Act
        Uni<Void> result = databaseExecutorRepository.save(executor);

        // Assert
        assertNotNull(result);
        assertDoesNotThrow(() -> result.await().indefinitely());
        assertNotNull(executorJpaRepository.saved);
        assertEquals("executor-1", executorJpaRepository.saved.getExecutorId());
    }

    @Test
    void findById_ExistingExecutor_ShouldReturnExecutor() {
        // Arrange
        var mockEntity = new ExecutorEntity();
        mockEntity.setExecutorId("executor-find-test");
        mockEntity.setExecutorType("find-type");
        mockEntity.setCommunicationType(CommunicationType.REST);
        mockEntity.setEndpoint("http://localhost:8081");

        executorJpaRepository.byId = mockEntity;

        // Act
        Uni<Optional<ExecutorInfo>> result = databaseExecutorRepository.findById("executor-find-test");

        // Assert
        assertNotNull(result);
        Optional<ExecutorInfo> executor = result.await().indefinitely();
        assertTrue(executor.isPresent());
        assertEquals("executor-find-test", executor.get().executorId());
    }

    @Test
    void findAll_WithMultipleExecutors_ShouldReturnAll() {
        // Arrange
        var mockEntity1 = new ExecutorEntity();
        mockEntity1.setExecutorId("executor-all-1");
        mockEntity1.setExecutorType("all-type");
        mockEntity1.setCommunicationType(CommunicationType.GRPC);
        mockEntity1.setEndpoint("http://localhost:8082");

        var mockEntity2 = new ExecutorEntity();
        mockEntity2.setExecutorId("executor-all-2");
        mockEntity2.setExecutorType("all-type");
        mockEntity2.setCommunicationType(CommunicationType.REST);
        mockEntity2.setEndpoint("http://localhost:8083");

        executorJpaRepository.all = List.of(mockEntity1, mockEntity2);

        // Act
        Uni<List<ExecutorInfo>> result = databaseExecutorRepository.findAll();

        // Assert
        assertNotNull(result);
        List<ExecutorInfo> executors = result.await().indefinitely();
        assertEquals(2, executors.size());
    }

    @Test
    void delete_ExistingExecutor_ShouldRemoveExecutor() {
        // Act
        Uni<Void> result = databaseExecutorRepository.delete("executor-delete-test");

        // Assert
        assertNotNull(result);
        assertDoesNotThrow(() -> result.await().indefinitely());
        assertEquals("executor-delete-test", executorJpaRepository.deletedId);
    }

    static class FakeExecutorJpaRepository extends ExecutorJpaRepositoryImpl {
        ExecutorEntity saved;
        ExecutorEntity byId;
        List<ExecutorEntity> all = new ArrayList<>();
        String deletedId;

        @Override
        public Uni<Void> save(ExecutorEntity executor) {
            saved = executor;
            return Uni.createFrom().voidItem();
        }

        @Override
        public Uni<ExecutorEntity> findById(String executorId) {
            return Uni.createFrom().item(byId);
        }

        @Override
        public Uni<List<ExecutorEntity>> getAllExecutors() {
            return Uni.createFrom().item(all);
        }

        @Override
        public Uni<Void> deleteById(String executorId) {
            deletedId = executorId;
            return Uni.createFrom().voidItem();
        }
    }
}
