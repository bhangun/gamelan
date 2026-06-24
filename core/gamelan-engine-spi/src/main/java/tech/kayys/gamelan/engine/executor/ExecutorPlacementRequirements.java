package tech.kayys.gamelan.engine.executor;

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import tech.kayys.gamelan.engine.collaboration.CollaborationContext;
import tech.kayys.gamelan.engine.collaboration.CollaborationParticipant;
import tech.kayys.gamelan.engine.collaboration.ParticipantIsolation;
import tech.kayys.gamelan.engine.collaboration.ParticipantKind;
import tech.kayys.gamelan.engine.collaboration.ParticipantRuntime;
import tech.kayys.gamelan.engine.error.ErrorCode;
import tech.kayys.gamelan.engine.error.GamelanException;
import tech.kayys.gamelan.engine.protocol.CommunicationType;

/**
 * Runtime placement constraints derived from a collaboration envelope.
 */
public record ExecutorPlacementRequirements(
        Set<ParticipantRuntime> runtimes,
        Set<ParticipantIsolation> isolations,
        Set<ParticipantKind> participantKinds,
        Set<String> roles) {

    public static final String METADATA_RUNTIMES_KEY = "gamelan.placement.runtimes";
    public static final String METADATA_ISOLATIONS_KEY = "gamelan.placement.isolations";
    public static final String METADATA_PARTICIPANT_KINDS_KEY = "gamelan.placement.participant-kinds";
    public static final String METADATA_ROLES_KEY = "gamelan.placement.roles";

    public ExecutorPlacementRequirements {
        runtimes = immutableEnumSet(runtimes);
        isolations = immutableEnumSet(isolations);
        participantKinds = immutableEnumSet(participantKinds);
        roles = immutableStringSet(roles);
    }

    public static ExecutorPlacementRequirements none() {
        return new ExecutorPlacementRequirements(Set.of(), Set.of(), Set.of(), Set.of());
    }

    public static ExecutorPlacementRequirements fromContext(Optional<CollaborationContext> collaboration) {
        return collaboration
                .map(ExecutorPlacementRequirements::fromContext)
                .orElseGet(ExecutorPlacementRequirements::none);
    }

    public static ExecutorPlacementRequirements fromContext(CollaborationContext collaboration) {
        if (collaboration == null) {
            return none();
        }

        EnumSet<ParticipantRuntime> runtimes = EnumSet.noneOf(ParticipantRuntime.class);
        EnumSet<ParticipantIsolation> isolations = EnumSet.noneOf(ParticipantIsolation.class);
        EnumSet<ParticipantKind> kinds = EnumSet.noneOf(ParticipantKind.class);
        LinkedHashSet<String> roles = new LinkedHashSet<>();

        addRequirements(collaboration.initiator(), runtimes, isolations, kinds, roles);
        collaboration.participants()
                .forEach(participant -> addRequirements(participant, runtimes, isolations, kinds, roles));

        return new ExecutorPlacementRequirements(runtimes, isolations, kinds, roles);
    }

    public boolean isEmpty() {
        return runtimes.isEmpty()
                && isolations.isEmpty()
                && participantKinds.isEmpty()
                && roles.isEmpty();
    }

    public boolean matches(ExecutorInfo executor) {
        if (executor == null) {
            return false;
        }
        if (isEmpty()) {
            return true;
        }

        Map<String, String> metadata = executor.metadata();
        Set<ParticipantRuntime> supportedRuntimes = supportedRuntimes(executor, metadata);
        Set<ParticipantIsolation> supportedIsolations = supportedIsolations(executor, metadata);
        Set<ParticipantKind> supportedKinds = parseEnumSet(
                ParticipantKind.class,
                metadata.get(METADATA_PARTICIPANT_KINDS_KEY),
                "executor participant kinds");
        Set<String> supportedRoles = parseStringSet(metadata.get(METADATA_ROLES_KEY));

        return supportsAll(supportedRuntimes, runtimes)
                && supportsAll(supportedIsolations, isolations)
                && supportsOptional(supportedKinds, participantKinds)
                && supportsOptional(supportedRoles, roles);
    }

    public Map<String, Object> toContextMap() {
        return Map.of(
                "runtimes", runtimes.stream().map(Enum::name).toList(),
                "isolations", isolations.stream().map(Enum::name).toList(),
                "participantKinds", participantKinds.stream().map(Enum::name).toList(),
                "roles", roles.stream().toList());
    }

    private static void addRequirements(
            CollaborationParticipant participant,
            EnumSet<ParticipantRuntime> runtimes,
            EnumSet<ParticipantIsolation> isolations,
            EnumSet<ParticipantKind> kinds,
            Set<String> roles) {
        if (participant == null || !isExecutableParticipant(participant.kind())) {
            return;
        }

        kinds.add(participant.kind());
        if (participant.runtime() != ParticipantRuntime.UNSPECIFIED) {
            runtimes.add(participant.runtime());
        }
        if (participant.isolation() != ParticipantIsolation.UNSPECIFIED) {
            isolations.add(participant.isolation());
        }
        roles.addAll(participant.roles());
    }

    private static boolean isExecutableParticipant(ParticipantKind kind) {
        return kind == ParticipantKind.AGENT
                || kind == ParticipantKind.SERVICE
                || kind == ParticipantKind.TOOL
                || kind == ParticipantKind.SYSTEM;
    }

    private static Set<ParticipantRuntime> supportedRuntimes(
            ExecutorInfo executor,
            Map<String, String> metadata) {
        Set<ParticipantRuntime> configured = parseEnumSet(
                ParticipantRuntime.class,
                metadata.get(METADATA_RUNTIMES_KEY),
                "executor runtimes");
        if (!configured.isEmpty()) {
            return configured;
        }

        return switch (executor.communicationType()) {
            case LOCAL -> Set.of(ParticipantRuntime.LOCAL);
            case REST, GRPC, KAFKA -> Set.of(ParticipantRuntime.REMOTE, ParticipantRuntime.DISTRIBUTED);
            case UNSPECIFIED -> Set.of();
        };
    }

    private static Set<ParticipantIsolation> supportedIsolations(
            ExecutorInfo executor,
            Map<String, String> metadata) {
        Set<ParticipantIsolation> configured = parseEnumSet(
                ParticipantIsolation.class,
                metadata.get(METADATA_ISOLATIONS_KEY),
                "executor isolations");
        if (!configured.isEmpty()) {
            return configured;
        }

        return executor.communicationType() == CommunicationType.LOCAL
                ? Set.of(ParticipantIsolation.NONE)
                : Set.of();
    }

    private static <E extends Enum<E>> Set<E> parseEnumSet(
            Class<E> enumType,
            String raw,
            String field) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }

        EnumSet<E> values = EnumSet.noneOf(enumType);
        for (String token : split(raw)) {
            if (token.isBlank()) {
                continue;
            }
            try {
                values.add(Enum.valueOf(enumType, token.trim().replace('-', '_').toUpperCase()));
            } catch (IllegalArgumentException error) {
                throw new GamelanException(
                        ErrorCode.VALIDATION_FAILED,
                        "Unsupported " + field + ": " + token);
            }
        }
        return Collections.unmodifiableSet(values);
    }

    private static Set<String> parseStringSet(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }

        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (String token : split(raw)) {
            if (!token.isBlank()) {
                values.add(token.trim());
            }
        }
        return Collections.unmodifiableSet(values);
    }

    private static String[] split(String raw) {
        return raw.trim().split("[,;\\s]+");
    }

    private static boolean supportsAll(Set<?> supported, Set<?> required) {
        return required.isEmpty() || supported.containsAll(required);
    }

    private static boolean supportsOptional(Set<?> supported, Set<?> required) {
        return required.isEmpty() || supported.isEmpty() || supported.containsAll(required);
    }

    private static <E extends Enum<E>> Set<E> immutableEnumSet(Set<E> source) {
        if (source == null || source.isEmpty()) {
            return Set.of();
        }
        return Collections.unmodifiableSet(EnumSet.copyOf(source));
    }

    private static Set<String> immutableStringSet(Set<String> source) {
        if (source == null || source.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<String> copy = new LinkedHashSet<>();
        source.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .forEach(copy::add);
        return Collections.unmodifiableSet(copy);
    }
}
