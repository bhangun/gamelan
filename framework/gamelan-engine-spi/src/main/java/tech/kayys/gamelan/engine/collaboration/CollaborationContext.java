package tech.kayys.gamelan.engine.collaboration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import tech.kayys.gamelan.engine.error.ErrorCode;
import tech.kayys.gamelan.engine.error.GamelanException;
import tech.kayys.gamelan.engine.payload.ExecutionPayloads;

/**
 * Typed collaboration envelope for multi-agent and human-in-the-loop work.
 */
public record CollaborationContext(
        String collaborationId,
        CollaborationParticipant initiator,
        List<CollaborationParticipant> participants,
        Map<String, Object> metadata) {

    public CollaborationContext {
        collaborationId = optionalText(collaborationId);
        List<CollaborationParticipant> participantCopy = participants != null
                ? new ArrayList<>(participants)
                : List.of();
        if (participantCopy.stream().anyMatch(java.util.Objects::isNull)) {
            throw new GamelanException(ErrorCode.VALIDATION_FAILED, "Collaboration participants cannot contain null");
        }
        participants = List.copyOf(participantCopy);
        metadata = ExecutionPayloads.immutableMap(metadata);
    }

    public static CollaborationContext of(
            String collaborationId,
            CollaborationParticipant initiator,
            List<CollaborationParticipant> participants,
            Map<String, Object> metadata) {
        return new CollaborationContext(collaborationId, initiator, participants, metadata);
    }

    public static Optional<CollaborationContext> fromContextValue(Object value) {
        if (value == null) {
            return Optional.empty();
        }
        if (value instanceof CollaborationContext context) {
            return Optional.of(context);
        }
        if (value instanceof Map<?, ?> map) {
            return Optional.of(fromMap(map));
        }
        throw new GamelanException(
                ErrorCode.VALIDATION_FAILED,
                "Collaboration context must be a map or CollaborationContext");
    }

    public boolean hasParticipants() {
        return !participants.isEmpty() || initiator != null;
    }

    public List<CollaborationParticipant> participantsByKind(ParticipantKind kind) {
        if (kind == null) {
            return List.of();
        }
        return participants.stream()
                .filter(participant -> participant.kind() == kind)
                .toList();
    }

    public boolean hasSandboxedParticipant() {
        return participants.stream()
                .anyMatch(participant -> participant.isolation() == ParticipantIsolation.SANDBOX)
                || (initiator != null && initiator.isolation() == ParticipantIsolation.SANDBOX);
    }

    public Map<String, Object> toContextMap() {
        return Map.of(
                "collaborationId", collaborationId != null ? collaborationId : "",
                "initiator", initiator != null ? initiator.toContextMap() : Map.of(),
                "participants", participants.stream()
                        .map(CollaborationParticipant::toContextMap)
                        .toList(),
                "metadata", metadata);
    }

    private static CollaborationContext fromMap(Map<?, ?> source) {
        CollaborationParticipant initiator = participantValue(source.get("initiator")).orElse(null);
        List<CollaborationParticipant> participants = participantsValue(source.get("participants"));

        return new CollaborationContext(
                textOrNull(source.get("collaborationId"), source.get("id")),
                initiator,
                participants,
                metadataMap(source.get("metadata")));
    }

    private static Optional<CollaborationParticipant> participantValue(Object raw) {
        if (raw == null) {
            return Optional.empty();
        }
        if (raw instanceof CollaborationParticipant participant) {
            return Optional.of(participant);
        }
        if (raw instanceof Map<?, ?> map) {
            return Optional.of(CollaborationParticipant.fromMap(map));
        }
        throw new GamelanException(
                ErrorCode.VALIDATION_FAILED,
                "Collaboration participant must be a map or CollaborationParticipant");
    }

    private static List<CollaborationParticipant> participantsValue(Object raw) {
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof Iterable<?> iterable)) {
            throw new GamelanException(ErrorCode.VALIDATION_FAILED, "Collaboration participants must be a collection");
        }

        List<CollaborationParticipant> participants = new ArrayList<>();
        for (Object item : iterable) {
            if (item == null) {
                throw new GamelanException(
                        ErrorCode.VALIDATION_FAILED,
                        "Collaboration participants cannot contain null");
            }
            participantValue(item).ifPresent(participants::add);
        }
        return List.copyOf(participants);
    }

    private static Map<String, Object> metadataMap(Object raw) {
        if (raw == null) {
            return Map.of();
        }
        if (raw instanceof Map<?, ?> map) {
            return ExecutionPayloads.immutableStringKeyMap(map);
        }
        throw new GamelanException(ErrorCode.VALIDATION_FAILED, "Collaboration metadata must be a map");
    }

    private static String textOrNull(Object primary, Object fallback) {
        Object value = primary != null ? primary : fallback;
        return value != null ? optionalText(String.valueOf(value)) : null;
    }

    private static String optionalText(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
