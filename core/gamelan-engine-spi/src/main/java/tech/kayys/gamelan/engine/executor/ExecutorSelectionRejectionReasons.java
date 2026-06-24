package tech.kayys.gamelan.engine.executor;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Stable executor-selection rejection reason catalog used by registries,
 * schedulers, task dead letters, and operator tooling.
 */
public final class ExecutorSelectionRejectionReasons {

    public static final String NO_EXECUTOR = "no-executor";
    public static final String MISSING_EXECUTOR = "missing-executor";
    public static final String EXECUTOR_TYPE_MISMATCH = "executor-type-mismatch";
    public static final String UNHEALTHY = "unhealthy";
    public static final String INVALID_PLACEMENT_METADATA = "invalid-placement-metadata";
    public static final String PLACEMENT_MISMATCH = "placement-mismatch";
    public static final String CAPABILITY_MISMATCH = "capability-mismatch";
    public static final String REQUIRED_CAPABILITY_MISMATCH = "required-capability-mismatch";
    public static final String EXCLUDED_CAPABILITY_PRESENT = "excluded-capability-present";
    public static final String RESOURCE_MISMATCH = "resource-mismatch";
    public static final String RESOURCE_MISSING_METADATA = "resource-missing-metadata";
    public static final String RESOURCE_INVALID_METADATA = "resource-invalid-metadata";
    public static final String RESOURCE_INSUFFICIENT = "resource-insufficient";
    public static final String RESOURCE_LOCALITY_MISMATCH = "resource-locality-mismatch";
    public static final String INVALID_CAPACITY_METADATA = "invalid-capacity-metadata";
    public static final String CAPACITY_SATURATED = "capacity-saturated";
    public static final String NO_COMPATIBLE_EXECUTOR = "no-compatible-executor";

    private static final List<String> PRIMARY_REASON_PRIORITY = List.of(
            CAPACITY_SATURATED,
            INVALID_CAPACITY_METADATA,
            UNHEALTHY,
            RESOURCE_MISMATCH,
            CAPABILITY_MISMATCH,
            PLACEMENT_MISMATCH,
            INVALID_PLACEMENT_METADATA,
            EXECUTOR_TYPE_MISMATCH,
            MISSING_EXECUTOR);

    private static final Set<String> PERMANENT_REASONS = Set.of(
            INVALID_CAPACITY_METADATA);

    private ExecutorSelectionRejectionReasons() {
    }

    public static String primaryReason(Map<String, Integer> rejectionCounts) {
        if (rejectionCounts == null || rejectionCounts.isEmpty()) {
            return NO_EXECUTOR;
        }
        for (String reason : PRIMARY_REASON_PRIORITY) {
            if (count(rejectionCounts, reason) > 0) {
                return reason;
            }
        }
        return NO_COMPATIBLE_EXECUTOR;
    }

    public static boolean isPermanent(String reason) {
        return PERMANENT_REASONS.contains(reason);
    }

    public static List<String> primaryReasonPriority() {
        return PRIMARY_REASON_PRIORITY;
    }

    public static Set<String> permanentReasons() {
        return PERMANENT_REASONS;
    }

    private static int count(Map<String, Integer> rejectionCounts, String reason) {
        Integer count = rejectionCounts.get(reason);
        return count != null ? Math.max(0, count) : 0;
    }
}
