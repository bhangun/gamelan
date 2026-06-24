package tech.kayys.gamelan.runtime.resource;

/**
 * Normalizes runtime capability diagnostic detail limits for probe and startup payloads.
 */
public final class RuntimeCapabilityIssueDetailLimits {

    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 100;

    private RuntimeCapabilityIssueDetailLimits() {
    }

    public static int normalize(Integer configuredLimit) {
        if (configuredLimit == null) {
            return DEFAULT_LIMIT;
        }
        return Math.min(MAX_LIMIT, Math.max(0, configuredLimit));
    }
}
