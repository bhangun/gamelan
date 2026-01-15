package tech.kayys.silat.registry.persistence;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tech.kayys.silat.model.CommunicationType;
import tech.kayys.silat.model.ExecutorInfo;
import tech.kayys.silat.registry.repository.ExecutorJpaRepositoryImpl;
import tech.kayys.silat.registry.entity.ExecutorEntity;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

@QuarkusTest
class DatabaseExecutorRepositoryTest {

    @InjectMock
    ExecutorJpaRepositoryImpl executorJpaRepository;

    @Inject
    DatabaseExecutorRepository databaseExecutorRepository;

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

        Mockito.when(executorJpaRepository.save(any())).thenReturn(Uni.createFrom().voidItem());

        // Act
        Uni<Void> result = databaseExecutorRepository.save(executor);

        // Assert
        assertNotNull(result);
        result.subscribe().with(item -> {
        }, failure -> fail(failure));
        Mockito.verify(executorJpaRepository).save(any(ExecutorEntity.class));
    }

    @Test
    void findById_ExistingExecutor_ShouldReturnExecutor() {
        // Arrange
        var mockEntity = new ExecutorEntity();
        mockEntity.setExecutorId("executor-find-test");
        mockEntity.setExecutorType("find-type");
        mockEntity.setCommunicationType(CommunicationType.REST);
        mockEntity.setEndpoint("http://localhost:8081");

        Mockito.when(executorJpaRepository.findById(eq("executor-find-test")))
                .thenReturn(Uni.createFrom().item(mockEntity));

        // Act
        Uni<Optional<ExecutorInfo>> result = databaseExecutorRepository.findById("executor-find-test");

        // Assert
        assertNotNull(result);
        result.subscribe().with(opt -> {
            assertTrue(opt.isPresent());
            assertEquals("executor-find-test", opt.get().executorId());
        }, failure -> fail(failure));
    }

    @Test
    void findAll_WithMultipleExecutors_ShouldReturnAll() {
        // Arrange
        var mockEntity1 = new ExecutorEntity();
        mockEntity1.setExecutorId("executor-all-1");

        var mockEntity2 = new ExecutorEntity();
        mockEntity2.setExecutorId("executor-all-2");

        Mockito.when(executorJpaRepository.getAllExecutors())
                .thenReturn(Uni.createFrom().item(List.of(mockEntity1, mockEntity2)));

        // Act
        Uni<List<ExecutorInfo>> result = databaseExecutorRepository.findAll();

        // Assert
        assertNotNull(result);
        result.subscribe().with(list -> {
            assertEquals(2, list.size());
        }, failure -> fail(failure));
    }

    @Test
    void delete_ExistingExecutor_ShouldRemoveExecutor() {
        // Arrange
        Mockito.when(executorJpaRepository.deleteById(eq("executor-delete-test")))
                .thenReturn(Uni.createFrom().voidItem());

        // Act
        Uni<Void> result = databaseExecutorRepository.delete("executor-delete-test");

        // Assert
        assertNotNull(result);
        result.subscribe().with(item -> {
        }, failure -> fail(failure));
        Mockito.verify(executorJpaRepository).deleteById("executor-delete-test");
    }
}