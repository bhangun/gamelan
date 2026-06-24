package tech.kayys.gamelan.engine.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import tech.kayys.gamelan.engine.error.ErrorCode;
import tech.kayys.gamelan.engine.error.GamelanException;
import tech.kayys.gamelan.engine.protocol.CommunicationType;

class ExecutorResourceRequirementsTest {

    @Test
    void fromContextParsesResourceAndLocalityRequirements() {
        ExecutorResourceRequirements requirements = ExecutorResourceRequirements.fromContext(Map.of(
                ExecutorResourceRequirements.MIN_MEMORY_MB_KEY, "4096",
                ExecutorResourceRequirements.MIN_CPU_CORES_KEY, 2.5,
                ExecutorResourceRequirements.REGIONS_KEY, List.of(" US-East-1 ", "EU-West-1"),
                ExecutorResourceRequirements.DATA_RESIDENCY_KEY, " US EU "));

        assertEquals(4096L, requirements.minMemoryMb());
        assertEquals(2.5D, requirements.minCpuCores());
        assertEquals(Set.of("us-east-1", "eu-west-1"), requirements.regions());
        assertEquals(Set.of("us", "eu"), requirements.dataResidencies());
    }

    @Test
    void rejectsInvalidRequirementValues() {
        GamelanException error = assertThrows(
                GamelanException.class,
                () -> ExecutorResourceRequirements.fromContext(Map.of(
                        ExecutorResourceRequirements.MIN_MEMORY_MB_KEY, -1)));

        assertEquals(ErrorCode.VALIDATION_FAILED, error.getErrorCode());
        assertTrue(error.getSafeMessage().contains(ExecutorResourceRequirements.MIN_MEMORY_MB_KEY));
    }

    @Test
    void evaluateAcceptsMatchingExecutorResources() {
        ExecutorResourceRequirements requirements = new ExecutorResourceRequirements(
                4096L,
                2.0D,
                Set.of("us-east-1"),
                Set.of("us"));

        ExecutorResourceRequirements.ResourceMatch match = requirements.evaluate(executor(
                "agent-1",
                Map.of(
                        ExecutorResourceRequirements.METADATA_MEMORY_MB_KEY, "8192",
                        ExecutorResourceRequirements.METADATA_CPU_CORES_KEY, "4",
                        ExecutorResourceRequirements.METADATA_REGIONS_KEY, "us-east-1,us-west-2",
                        ExecutorResourceRequirements.METADATA_DATA_RESIDENCIES_KEY, "us")));

        assertTrue(match.matched());
        assertTrue(match.missingMetadataKeys().isEmpty());
        assertTrue(match.insufficientResourceKeys().isEmpty());
    }

    @Test
    void evaluateExplainsMissingInvalidInsufficientAndLocalityMismatches() {
        ExecutorResourceRequirements requirements = new ExecutorResourceRequirements(
                4096L,
                2.0D,
                Set.of("eu-west-1"),
                Set.of("eu"));

        ExecutorResourceRequirements.ResourceMatch match = requirements.evaluate(executor(
                "agent-1",
                Map.of(
                        ExecutorResourceRequirements.METADATA_MEMORY_MB_KEY, "1024",
                        ExecutorResourceRequirements.METADATA_CPU_CORES_KEY, "many",
                        ExecutorResourceRequirements.METADATA_REGIONS_KEY, "us-east-1")));

        assertFalse(match.matched());
        assertEquals(Set.of(ExecutorResourceRequirements.METADATA_DATA_RESIDENCIES_KEY), match.missingMetadataKeys());
        assertEquals(Set.of(ExecutorResourceRequirements.METADATA_CPU_CORES_KEY), match.invalidMetadataKeys());
        assertEquals(Set.of(ExecutorResourceRequirements.METADATA_MEMORY_MB_KEY), match.insufficientResourceKeys());
        assertEquals(Set.of(ExecutorResourceRequirements.METADATA_REGIONS_KEY), match.mismatchedLocalityKeys());
    }

    private static ExecutorInfo executor(String executorId, Map<String, String> metadata) {
        return new ExecutorInfo(
                executorId,
                "agent",
                CommunicationType.GRPC,
                "endpoint",
                Duration.ofSeconds(30),
                metadata);
    }
}
