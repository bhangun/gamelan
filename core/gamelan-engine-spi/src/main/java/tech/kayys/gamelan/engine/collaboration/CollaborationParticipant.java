package tech.kayys.gamelan.engine.collaboration;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import tech.kayys.gamelan.engine.error.ErrorCode;
import tech.kayys.gamelan.engine.error.GamelanException;
import tech.kayys.gamelan.engine.payload.ExecutionPayloads;

/**
 * A human, agent, tool, service, or system participant in a workflow execution.
 */
public record CollaborationParticipant(
        String id,
        ParticipantKind kind,
        ParticipantRuntime runtime,
        ParticipantIsolation isolation,
        Set<String> roles,
        Map<String, Object> metadata) {

    public CollaborationParticipant {
        id = requireText(id, "Participant id");
        kind = Objects.requireNonNull(kind, "Participant kind cannot be null");
        runtime = runtime != null ? runtime : ParticipantRuntime.UNSPECIFIED;
        isolation = isolation != null ? isolation : ParticipantIsolation.UNSPECIFIED;
        roles = immutableRoles(roles);
        metadata = ExecutionPayloads.immutableMap(metadata);
    }

    public static CollaborationParticipant human(String id, String... roles) {
        return new CollaborationParticipant(
                id,
                ParticipantKind.HUMAN,
                ParticipantRuntime.EXTERNAL,
                ParticipantIsolation.UNSPECIFIED,
                rolesFromArray(roles),
                Map.of());
    }

    public static CollaborationParticipant agent(
            String id,
            ParticipantRuntime runtime,
            ParticipantIsolation isolation,
            String... roles) {
        return new CollaborationParticipant(
                id,
                ParticipantKind.AGENT,
                runtime,
                isolation,
                rolesFromArray(roles),
                Map.of());
    }

    static CollaborationParticipant fromMap(Map<?, ?> source) {
        if (source == null) {
            throw new GamelanException(ErrorCode.VALIDATION_FAILED, "Participant map is required");
        }

        return new CollaborationParticipant(
                text(source.get("id"), "Participant id"),
                enumValue(ParticipantKind.class, source.get("kind"), "participant kind"),
                enumValueOrDefault(
                        ParticipantRuntime.class,
                        source.get("runtime"),
                        ParticipantRuntime.UNSPECIFIED,
                        "participant runtime"),
                enumValueOrDefault(
                        ParticipantIsolation.class,
                        source.get("isolation"),
                        ParticipantIsolation.UNSPECIFIED,
                        "participant isolation"),
                roleSet(source.get("roles")),
                metadataMap(source.get("metadata")));
    }

    public Map<String, Object> toContextMap() {
        return Map.of(
                "id", id,
                "kind", kind.name(),
                "runtime", runtime.name(),
                "isolation", isolation.name(),
                "roles", roles,
                "metadata", metadata);
    }

    private static Set<String> immutableRoles(Collection<String> source) {
        if (source == null || source.isEmpty()) {
            return Set.of();
        }

        LinkedHashSet<String> copy = new LinkedHashSet<>();
        for (String role : source) {
            String normalized = requireText(role, "Participant role");
            copy.add(normalized);
        }
        return Collections.unmodifiableSet(copy);
    }

    private static Set<String> roleSet(Object raw) {
        if (raw == null) {
            return Set.of();
        }
        if (raw instanceof String role) {
            return Set.of(requireText(role, "Participant role"));
        }
        if (raw instanceof Collection<?> collection) {
            LinkedHashSet<String> roles = new LinkedHashSet<>();
            for (Object role : collection) {
                roles.add(roleText(role));
            }
            return Collections.unmodifiableSet(roles);
        }
        throw new GamelanException(ErrorCode.VALIDATION_FAILED, "Participant roles must be a string or collection");
    }

    private static Set<String> rolesFromArray(String... roles) {
        if (roles == null || roles.length == 0) {
            return Set.of();
        }

        LinkedHashSet<String> copy = new LinkedHashSet<>();
        for (String role : roles) {
            copy.add(requireText(role, "Participant role"));
        }
        return Collections.unmodifiableSet(copy);
    }

    private static String roleText(Object value) {
        return requireText(value != null ? String.valueOf(value) : null, "Participant role");
    }

    private static Map<String, Object> metadataMap(Object raw) {
        if (raw == null) {
            return Map.of();
        }
        if (raw instanceof Map<?, ?> map) {
            return ExecutionPayloads.immutableStringKeyMap(map);
        }
        throw new GamelanException(ErrorCode.VALIDATION_FAILED, "Participant metadata must be a map");
    }

    private static String text(Object value, String field) {
        return requireText(value != null ? String.valueOf(value) : null, field);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new GamelanException(ErrorCode.VALIDATION_FAILED, field + " is required");
        }
        return value.trim();
    }

    private static <E extends Enum<E>> E enumValue(
            Class<E> enumType,
            Object raw,
            String field) {
        if (raw == null || String.valueOf(raw).isBlank()) {
            throw new GamelanException(ErrorCode.VALIDATION_FAILED, field + " is required");
        }
        return enumValueOrDefault(enumType, raw, null, field);
    }

    private static <E extends Enum<E>> E enumValueOrDefault(
            Class<E> enumType,
            Object raw,
            E defaultValue,
            String field) {
        if (raw == null || String.valueOf(raw).isBlank()) {
            return defaultValue;
        }

        String normalized = String.valueOf(raw).trim().replace('-', '_').toUpperCase();
        try {
            return Enum.valueOf(enumType, normalized);
        } catch (IllegalArgumentException error) {
            throw new GamelanException(
                    ErrorCode.VALIDATION_FAILED,
                    "Unsupported " + field + ": " + raw);
        }
    }
}
