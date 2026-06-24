package tech.kayys.gamelan.engine.saga;

/**
 * Canonical execution history event types emitted by compensation lifecycle code.
 */
public final class CompensationEventTypes {
    public static final String COMPENSATION_STARTED = "COMPENSATION_STARTED";
    public static final String COMPENSATION_NODE_CLAIMED = "COMPENSATION_NODE_CLAIMED";
    public static final String COMPENSATION_NODE_CLAIM_EXPIRED = "COMPENSATION_NODE_CLAIM_EXPIRED";
    public static final String COMPENSATION_NODE_CLAIM_RELEASED = "COMPENSATION_NODE_CLAIM_RELEASED";
    public static final String COMPENSATION_NODE_CLAIM_SKIPPED = "COMPENSATION_NODE_CLAIM_SKIPPED";
    public static final String COMPENSATION_NODE_COMPLETED = "COMPENSATION_NODE_COMPLETED";
    public static final String COMPENSATION_NODE_FAILED = "COMPENSATION_NODE_FAILED";
    public static final String COMPENSATION_NODE_SKIPPED = "COMPENSATION_NODE_SKIPPED";
    public static final String COMPENSATION_COMPLETED = "COMPENSATION_COMPLETED";
    public static final String COMPENSATION_FAILED = "COMPENSATION_FAILED";

    private CompensationEventTypes() {
    }
}
