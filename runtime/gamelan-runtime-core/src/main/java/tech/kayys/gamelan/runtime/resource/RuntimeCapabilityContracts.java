package tech.kayys.gamelan.runtime.resource;

import java.util.List;
import java.util.Locale;

import tech.kayys.gamelan.runtime.resource.RuntimeCapabilitiesResource.RuntimeProfile;

/**
 * Resolves named runtime capability contracts from explicit config or profiles.
 */
final class RuntimeCapabilityContracts {

    static final String AUTO = "auto";
    static final String NONE = "none";
    static final String LOCAL = "local";
    static final String OFFLINE_AGENT = "offline-agent";
    static final String STANDALONE = "standalone";
    static final String DISTRIBUTED = "distributed";
    static final String PRODUCTION = "production";

    private RuntimeCapabilityContracts() {
    }

    static RuntimeCapabilityContract resolve(String configuredContract, RuntimeProfile profile) {
        String normalized = normalizeContractName(configuredContract);
        if (normalized == null || AUTO.equals(normalized)) {
            return contractFor(inferContractName(profile), AUTO);
        }
        return contractFor(normalized, "configured");
    }

    private static RuntimeCapabilityContract contractFor(String name, String selectedBy) {
        return switch (name) {
            case NONE -> new RuntimeCapabilityContract(
                    NONE,
                    selectedBy,
                    false,
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    false,
                    false,
                    false);
            case OFFLINE_AGENT -> new RuntimeCapabilityContract(
                    OFFLINE_AGENT,
                    selectedBy,
                    true,
                    List.of("file"),
                    List.of("file"),
                    List.of(),
                    List.of(),
                    List.of("local"),
                    List.of("local", "default"),
                    List.of("file"),
                    false,
                    true,
                    true);
            case STANDALONE -> new RuntimeCapabilityContract(
                    STANDALONE,
                    selectedBy,
                    true,
                    List.of("file", "postgres", "custom"),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of("local", "default", "custom"),
                    List.of("file", "postgres", "custom"),
                    false,
                    false,
                    false);
            case DISTRIBUTED -> new RuntimeCapabilityContract(
                    DISTRIBUTED,
                    selectedBy,
                    true,
                    List.of("postgres", "custom"),
                    List.of(),
                    List.of("memory"),
                    List.of("redis", "postgres", "custom"),
                    List.of("redis"),
                    List.of("kafka", "custom"),
                    List.of("postgres", "custom"),
                    true,
                    true,
                    true);
            case PRODUCTION -> new RuntimeCapabilityContract(
                    PRODUCTION,
                    selectedBy,
                    true,
                    List.of("postgres", "custom"),
                    List.of("postgres", "custom"),
                    List.of("memory"),
                    List.of("redis", "postgres", "custom"),
                    List.of("redis"),
                    List.of("kafka", "custom"),
                    List.of("postgres", "custom"),
                    true,
                    true,
                    true);
            case LOCAL -> new RuntimeCapabilityContract(
                    LOCAL,
                    selectedBy,
                    true,
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    false,
                    false,
                    false);
            default -> throw new IllegalArgumentException(
                    "Unsupported gamelan.runtime.capabilities.contract: " + name);
        };
    }

    private static String inferContractName(RuntimeProfile profile) {
        String profileName = profile != null ? profile.quarkusProfile() : null;
        String normalized = profileName == null ? "" : profileName.toLowerCase(Locale.ROOT);
        if (normalized.contains(DISTRIBUTED) || normalized.contains("cluster")) {
            return DISTRIBUTED;
        }
        if (normalized.contains("offline")) {
            return OFFLINE_AGENT;
        }
        if (normalized.contains("prod")) {
            return PRODUCTION;
        }
        if (normalized.contains(STANDALONE)) {
            return STANDALONE;
        }
        return LOCAL;
    }

    private static String normalizeContractName(String value) {
        if (value == null || value.isBlank()) {
            return AUTO;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT)
                .replace('_', '-')
                .replace(' ', '-');
        return switch (normalized) {
            case "off", "false", "disabled" -> NONE;
            case "automatic" -> AUTO;
            case "offline", "offline-local", "local-agent" -> OFFLINE_AGENT;
            case "prod" -> PRODUCTION;
            default -> normalized;
        };
    }
}
