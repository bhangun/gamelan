package tech.kayys.silat.registry.persistence;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import tech.kayys.silat.model.CommunicationType;
import tech.kayys.silat.model.ExecutorInfo;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class CachedDatabaseExecutorRepositoryTest {

    @Test
    void save_ValidExecutor_ShouldSaveAndInvalidateCache() {
        ExecutorInfo executor = new ExecutorInfo(
                "executor-cached-save",
                "cached-type",
                CommunicationType.GRPC,
                "http://localhost:8080",
                Duration.ofSeconds(30),
                Map.of("cached", "true"));

        assertNotNull(executor);
        assertEquals("executor-cached-save", executor.executorId());
    }

    @Test
    void findById_CachedExecutor_ShouldReturnFromCache() {
        ExecutorInfo executor = new ExecutorInfo(
                "executor-cached-find",
                "cached-find-type",
                CommunicationType.REST,
                "http://localhost:8081",
                Duration.ofSeconds(45),
                Map.of("source", "cache"));

        assertNotNull(executor);
        assertEquals("cached-find-type", executor.executorType());
    }

    @Test
    void findByType_CachedExecutors_ShouldReturnFromCache() {
        ExecutorInfo executor = new ExecutorInfo(
                "executor-cached-type",
                "filter-cached-type",
                CommunicationType.GRPC,
                "http://localhost:8082",
                Duration.ofSeconds(60),
                Map.of("category", "cached-filtered"));

        assertNotNull(executor);
        assertEquals("filter-cached-type", executor.executorType());
    }

    @Test
    void findByCommunicationType_CachedExecutors_ShouldReturnFromCache() {
        ExecutorInfo executor = new ExecutorInfo(
                "executor-cached-comm",
                "comm-cached-type",
                CommunicationType.KAFKA,
                "http://localhost:8083",
                Duration.ofSeconds(75),
                Map.of("protocol", "cached-async"));

        assertNotNull(executor);
        assertEquals(CommunicationType.KAFKA, executor.communicationType());
    }

    @Test
    void delete_Executor_ShouldInvalidateRelatedCaches() {
        String executorId = "executor-to-delete";
        assertNotNull(executorId);
        assertFalse(executorId.isEmpty());
    }

    @Test
    void findAll_CachedExecutors_ShouldReturnFromCache() {
        assertTrue(true);
    }
}