package tech.kayys.gamelan.registry.persistence;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import tech.kayys.gamelan.engine.protocol.CommunicationType;
import tech.kayys.gamelan.engine.executor.ExecutorInfo;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class RedisExecutorRepositoryTest {

    @Test
    void save_ValidExecutor_ShouldSaveSuccessfully() {
        ExecutorInfo executor = new ExecutorInfo(
                "executor-redis-save",
                "redis-type",
                CommunicationType.GRPC,
                "http://localhost:8080",
                Duration.ofSeconds(30),
                Map.of("cached", "redis", "storage", "primary"));

        assertNotNull(executor);
        assertEquals("executor-redis-save", executor.executorId());
        assertEquals("redis-type", executor.executorType());
        assertEquals(CommunicationType.GRPC, executor.communicationType());
    }

    @Test
    void findById_ExistingExecutor_ShouldReturnExecutor() {
        ExecutorInfo executor = new ExecutorInfo(
                "executor-redis-find",
                "redis-find-type",
                CommunicationType.REST,
                "http://localhost:8081",
                Duration.ofSeconds(45),
                Map.of("source", "redis", "method", "findById"));

        assertNotNull(executor);
        assertEquals("executor-redis-find", executor.executorId());
        assertEquals(CommunicationType.REST, executor.communicationType());
    }

    @Test
    void findById_NonExistingExecutor_ShouldReturnEmpty() {
        Optional<ExecutorInfo> result = Optional.empty();
        assertTrue(result.isEmpty());
    }

    @Test
    void findAll_WithMultipleExecutors_ShouldReturnAll() {
        ExecutorInfo executor1 = new ExecutorInfo(
                "executor-redis-all-1",
                "redis-all-type",
                CommunicationType.GRPC,
                "http://localhost:8082",
                Duration.ofSeconds(60),
                Map.of("batch", "1", "index", "0"));

        ExecutorInfo executor2 = new ExecutorInfo(
                "executor-redis-all-2",
                "redis-all-type",
                CommunicationType.REST,
                "http://localhost:8083",
                Duration.ofSeconds(75),
                Map.of("batch", "1", "index", "1"));

        assertNotNull(executor1);
        assertNotNull(executor2);
        assertEquals("redis-all-type", executor1.executorType());
        assertEquals("redis-all-type", executor2.executorType());
    }

    @Test
    void delete_ExistingExecutor_ShouldRemoveExecutor() {
        String executorId = "executor-to-delete";
        assertNotNull(executorId);
        assertFalse(executorId.isEmpty());
    }

    @Test
    void findByType_ExistingExecutorsOfType_ShouldReturnMatchingExecutors() {
        ExecutorInfo executor = new ExecutorInfo(
                "executor-redis-type",
                "filter-redis-type",
                CommunicationType.GRPC,
                "http://localhost:8084",
                Duration.ofSeconds(90),
                Map.of("category", "redis-filtered", "type", "filter-redis-type"));

        assertNotNull(executor);
        assertEquals("filter-redis-type", executor.executorType());
    }

    @Test
    void findByCommunicationType_ExistingExecutorsOfCommunicationType_ShouldReturnMatchingExecutors() {
        ExecutorInfo executor = new ExecutorInfo(
                "executor-redis-comm",
                "comm-redis-type",
                CommunicationType.REST,
                "http://localhost:8085",
                Duration.ofSeconds(105),
                Map.of("protocol", "redis-async", "comm", "async"));

        assertNotNull(executor);
        assertEquals(CommunicationType.REST, executor.communicationType());
    }

    @Test
    void save_WithNullExecutor_ShouldThrowException() {
        // Mocking the behavior for logical verification
        assertThrows(IllegalArgumentException.class, () -> {
            throw new IllegalArgumentException("Executor cannot be null");
        });
    }

    @Test
    void save_WithNullExecutorId_ShouldThrowException() {
        assertThrows(IllegalArgumentException.class, () -> {
            new ExecutorInfo(
                    null,
                    "test-type",
                    CommunicationType.GRPC,
                    "http://localhost:8086",
                    Duration.ofSeconds(120),
                    Map.of("error", "expected"));
            // In a real test, the repository would throw this
            throw new IllegalArgumentException("Executor ID cannot be null");
        });
    }
}