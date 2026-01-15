package tech.kayys.silat.registry.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tech.kayys.silat.model.CommunicationType;
import tech.kayys.silat.model.ExecutorInfo;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryExecutorRepositoryTest {

    private InMemoryExecutorRepository repository;

    @BeforeEach
    void setUp() {
        repository = new InMemoryExecutorRepository();
    }

    @Test
    void save_ValidExecutor_ShouldBeStored() {
        ExecutorInfo executor = createExecutor("exec-1", "type-A", CommunicationType.GRPC);

        repository.save(executor).await().indefinitely();

        Optional<ExecutorInfo> found = repository.findById("exec-1").await().indefinitely();
        assertTrue(found.isPresent());
        assertEquals("exec-1", found.get().executorId());
    }

    @Test
    void save_NullExecutor_ShouldFail() {
        assertThrows(IllegalArgumentException.class, () -> {
            repository.save(null).await().indefinitely();
        });
    }

    @Test
    void save_EmptyId_ShouldFail() {
        ExecutorInfo executor = createExecutor("", "type-A", CommunicationType.REST);
        assertThrows(IllegalArgumentException.class, () -> {
            repository.save(executor).await().indefinitely();
        });
    }

    @Test
    void findById_ExistingId_ShouldReturnExecutor() {
        ExecutorInfo executor = createExecutor("exec-2", "type-B", CommunicationType.KAFKA);
        repository.save(executor).await().indefinitely();

        Optional<ExecutorInfo> result = repository.findById("exec-2").await().indefinitely();
        assertTrue(result.isPresent());
        assertEquals("type-B", result.get().executorType());
    }

    @Test
    void findById_NonExistingId_ShouldReturnEmpty() {
        Optional<ExecutorInfo> result = repository.findById("non-existent").await().indefinitely();
        assertFalse(result.isPresent());
    }

    @Test
    void findAll_ShouldReturnAllStoredExecutors() {
        repository.save(createExecutor("e1", "t1", CommunicationType.GRPC)).await().indefinitely();
        repository.save(createExecutor("e2", "t2", CommunicationType.REST)).await().indefinitely();

        List<ExecutorInfo> all = repository.findAll().await().indefinitely();
        assertEquals(2, all.size());
    }

    @Test
    void delete_ExistingId_ShouldRemoveExecutor() {
        repository.save(createExecutor("delete-me", "t1", CommunicationType.GRPC)).await().indefinitely();
        repository.delete("delete-me").await().indefinitely();

        Optional<ExecutorInfo> result = repository.findById("delete-me").await().indefinitely();
        assertFalse(result.isPresent());
    }

    @Test
    void findByType_ShouldReturnMatchingExecutors() {
        repository.save(createExecutor("e1", "type-X", CommunicationType.GRPC)).await().indefinitely();
        repository.save(createExecutor("e2", "type-Y", CommunicationType.REST)).await().indefinitely();
        repository.save(createExecutor("e3", "type-X", CommunicationType.KAFKA)).await().indefinitely();

        List<ExecutorInfo> typeX = repository.findByType("type-X").await().indefinitely();
        assertEquals(2, typeX.size());
        assertTrue(typeX.stream().allMatch(e -> e.executorType().equals("type-X")));
    }

    @Test
    void findByCommunicationType_ShouldReturnMatchingExecutors() {
        repository.save(createExecutor("e1", "t1", CommunicationType.GRPC)).await().indefinitely();
        repository.save(createExecutor("e2", "t2", CommunicationType.REST)).await().indefinitely();
        repository.save(createExecutor("e3", "t3", CommunicationType.GRPC)).await().indefinitely();

        List<ExecutorInfo> grpcExecutors = repository.findByCommunicationType(CommunicationType.GRPC).await()
                .indefinitely();
        assertEquals(2, grpcExecutors.size());
        assertTrue(grpcExecutors.stream().allMatch(e -> e.communicationType() == CommunicationType.GRPC));
    }

    private ExecutorInfo createExecutor(String id, String type, CommunicationType commType) {
        return new ExecutorInfo(
                id,
                type,
                commType,
                "http://localhost/test",
                Duration.ofSeconds(10),
                Map.of());
    }
}