package tech.kayys.gamelan.engine.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tech.kayys.gamelan.engine.collaboration.CollaborationContext;
import tech.kayys.gamelan.engine.collaboration.ParticipantIsolation;
import tech.kayys.gamelan.engine.collaboration.ParticipantKind;
import tech.kayys.gamelan.engine.collaboration.ParticipantRuntime;
import tech.kayys.gamelan.engine.error.ErrorCode;
import tech.kayys.gamelan.engine.error.GamelanException;
import tech.kayys.gamelan.engine.protocol.CommunicationType;

class ExecutorPlacementRequirementsTest {

    @Test
    void fromContextRequiresOnlyExecutableParticipants() {
        CollaborationContext collaboration = CollaborationContext.fromContextValue(Map.of(
                "participants", List.of(
                        Map.of(
                                "id", "agent:planner",
                                "kind", "agent",
                                "runtime", "distributed",
                                "isolation", "sandbox",
                                "roles", List.of("planner")),
                        Map.of(
                                "id", "human:approver",
                                "kind", "human",
                                "runtime", "external",
                                "roles", List.of("approver")))))
                .orElseThrow();

        ExecutorPlacementRequirements requirements = ExecutorPlacementRequirements.fromContext(collaboration);

        assertEquals(List.of(ParticipantRuntime.DISTRIBUTED), List.copyOf(requirements.runtimes()));
        assertEquals(List.of(ParticipantIsolation.SANDBOX), List.copyOf(requirements.isolations()));
        assertEquals(List.of(ParticipantKind.AGENT), List.copyOf(requirements.participantKinds()));
        assertEquals(List.of("planner"), List.copyOf(requirements.roles()));
    }

    @Test
    void matchesExecutorWithDeclaredDistributedSandboxCapabilities() {
        ExecutorPlacementRequirements requirements = ExecutorPlacementRequirements.fromContext(
                CollaborationContext.fromContextValue(Map.of(
                        "participants", List.of(Map.of(
                                "id", "agent:planner",
                                "kind", "agent",
                                "runtime", "distributed",
                                "isolation", "sandbox"))))
                        .orElseThrow());

        ExecutorInfo executor = executor(
                CommunicationType.GRPC,
                Map.of(
                        ExecutorPlacementRequirements.METADATA_RUNTIMES_KEY, "remote,distributed",
                        ExecutorPlacementRequirements.METADATA_ISOLATIONS_KEY, "container,sandbox"));

        assertTrue(requirements.matches(executor));
    }

    @Test
    void rejectsExecutorWithoutRequiredSandboxIsolation() {
        ExecutorPlacementRequirements requirements = ExecutorPlacementRequirements.fromContext(
                CollaborationContext.fromContextValue(Map.of(
                        "participants", List.of(Map.of(
                                "id", "agent:planner",
                                "kind", "agent",
                                "runtime", "distributed",
                                "isolation", "sandbox"))))
                        .orElseThrow());

        assertFalse(requirements.matches(executor(CommunicationType.GRPC, Map.of())));
    }

    @Test
    void remoteTransportCanSatisfyDistributedRuntimeWhenNoIsolationIsRequired() {
        ExecutorPlacementRequirements requirements = ExecutorPlacementRequirements.fromContext(
                CollaborationContext.fromContextValue(Map.of(
                        "participants", List.of(Map.of(
                                "id", "agent:planner",
                                "kind", "agent",
                                "runtime", "distributed"))))
                        .orElseThrow());

        assertTrue(requirements.matches(executor(CommunicationType.GRPC, Map.of())));
    }

    @Test
    void invalidExecutorPlacementMetadataFailsValidation() {
        ExecutorPlacementRequirements requirements = new ExecutorPlacementRequirements(
                java.util.Set.of(ParticipantRuntime.DISTRIBUTED),
                java.util.Set.of(),
                java.util.Set.of(),
                java.util.Set.of());

        GamelanException error = assertThrows(
                GamelanException.class,
                () -> requirements.matches(executor(
                        CommunicationType.GRPC,
                        Map.of(ExecutorPlacementRequirements.METADATA_RUNTIMES_KEY, "telepathy"))));

        assertEquals(ErrorCode.VALIDATION_FAILED, error.getErrorCode());
        assertEquals("Unsupported executor runtimes: telepathy", error.getSafeMessage());
    }

    private static ExecutorInfo executor(
            CommunicationType communicationType,
            Map<String, String> metadata) {
        return new ExecutorInfo(
                "executor-1",
                "agent",
                communicationType,
                "endpoint",
                Duration.ofSeconds(30),
                metadata);
    }
}
