package tech.kayys.gamelan.engine.collaboration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tech.kayys.gamelan.engine.error.ErrorCode;
import tech.kayys.gamelan.engine.error.GamelanException;

class CollaborationContextTest {

    @Test
    @SuppressWarnings("unchecked")
    void fromContextValueParsesMultiParticipantCollaboration() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("channel", "incident-room");
        metadata.put("nested", new HashMap<>(Map.of("priority", "high")));

        Map<String, Object> raw = Map.of(
                "collaborationId", "collab-1",
                "initiator", Map.of(
                        "id", "human:alice",
                        "kind", "human",
                        "runtime", "external",
                        "roles", List.of("requester")),
                "participants", List.of(
                        Map.of(
                                "id", "agent:planner",
                                "kind", "agent",
                                "runtime", "distributed",
                                "isolation", "sandbox",
                                "roles", List.of("planner", "reviewer")),
                        Map.of(
                                "id", "human:bob",
                                "kind", "human",
                                "roles", "approver")),
                "metadata", metadata);

        CollaborationContext context = CollaborationContext.fromContextValue(raw).orElseThrow();

        assertEquals("collab-1", context.collaborationId());
        assertEquals("human:alice", context.initiator().id());
        assertEquals(2, context.participants().size());
        assertEquals(1, context.participantsByKind(ParticipantKind.AGENT).size());
        assertEquals(1, context.participantsByKind(ParticipantKind.HUMAN).size());
        assertEquals(ParticipantRuntime.DISTRIBUTED, context.participants().getFirst().runtime());
        assertEquals(ParticipantIsolation.SANDBOX, context.participants().getFirst().isolation());
        assertEquals(List.of("planner", "reviewer"), List.copyOf(context.participants().getFirst().roles()));
        assertTrue(context.hasSandboxedParticipant());
        assertEquals("incident-room", context.metadata().get("channel"));

        metadata.put("channel", "mutated");
        assertEquals("incident-room", context.metadata().get("channel"));
        assertThrows(UnsupportedOperationException.class, () -> context.metadata().put("x", "y"));
        assertThrows(
                UnsupportedOperationException.class,
                () -> ((Map<String, Object>) context.metadata().get("nested")).put("priority", "low"));
    }

    @Test
    void fromContextValueReturnsEmptyWhenAbsent() {
        assertFalse(CollaborationContext.fromContextValue(null).isPresent());
    }

    @Test
    void participantDefaultsRuntimeAndIsolationWhenOmitted() {
        CollaborationContext context = CollaborationContext.fromContextValue(Map.of(
                "participants", List.of(Map.of("id", "agent:worker", "kind", "agent"))))
                .orElseThrow();

        CollaborationParticipant participant = context.participants().getFirst();
        assertEquals(ParticipantRuntime.UNSPECIFIED, participant.runtime());
        assertEquals(ParticipantIsolation.UNSPECIFIED, participant.isolation());
    }

    @Test
    void participantFactoriesDeduplicateRolesWithoutChangingOrder() {
        CollaborationParticipant participant = CollaborationParticipant.agent(
                "agent:planner",
                ParticipantRuntime.DISTRIBUTED,
                ParticipantIsolation.SANDBOX,
                "planner",
                "planner",
                "reviewer");

        assertEquals(List.of("planner", "reviewer"), List.copyOf(participant.roles()));
    }

    @Test
    void fromContextValueRejectsInvalidParticipantKind() {
        GamelanException error = assertThrows(
                GamelanException.class,
                () -> CollaborationContext.fromContextValue(Map.of(
                        "participants", List.of(Map.of("id", "x", "kind", "unknown")))));

        assertEquals(ErrorCode.VALIDATION_FAILED, error.getErrorCode());
        assertEquals("Unsupported participant kind: unknown", error.getSafeMessage());
    }

    @Test
    void fromContextValueRejectsNullParticipantRole() {
        List<Object> roles = new ArrayList<>();
        roles.add(null);

        GamelanException error = assertThrows(
                GamelanException.class,
                () -> CollaborationContext.fromContextValue(Map.of(
                        "participants", List.of(Map.of("id", "agent:x", "kind", "agent", "roles", roles)))));

        assertEquals(ErrorCode.VALIDATION_FAILED, error.getErrorCode());
        assertEquals("Participant role is required", error.getSafeMessage());
    }

    @Test
    void fromContextValueRejectsNullParticipantEntry() {
        List<Object> participants = new ArrayList<>();
        participants.add(null);

        GamelanException error = assertThrows(
                GamelanException.class,
                () -> CollaborationContext.fromContextValue(Map.of("participants", participants)));

        assertEquals(ErrorCode.VALIDATION_FAILED, error.getErrorCode());
        assertEquals("Collaboration participants cannot contain null", error.getSafeMessage());
    }
}
