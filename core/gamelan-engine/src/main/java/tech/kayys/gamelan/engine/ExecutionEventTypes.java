package tech.kayys.gamelan.engine;

import tech.kayys.gamelan.engine.saga.CompensationEventTypes;

public final class ExecutionEventTypes {

    public static final String RUN_CREATED = "RUN_CREATED";
    public static final String STATUS_CHANGED = "STATUS_CHANGED";
    public static final String RUN_COMPLETED = "RUN_COMPLETED";
    public static final String RUN_FAILED = "RUN_FAILED";
    public static final String NODE_COMPLETED = "NODE_COMPLETED";
    public static final String NODE_FAILED = "NODE_FAILED";
    public static final String NODE_RESULT_IGNORED = "NODE_RESULT_IGNORED";
    public static final String SIGNAL_RECEIVED = "SIGNAL_RECEIVED";
    public static final String SIGNAL_IGNORED = "SIGNAL_IGNORED";
    public static final String COMPENSATION_STARTED = CompensationEventTypes.COMPENSATION_STARTED;
    public static final String COMPENSATION_NODE_CLAIMED = CompensationEventTypes.COMPENSATION_NODE_CLAIMED;
    public static final String COMPENSATION_NODE_CLAIM_EXPIRED =
            CompensationEventTypes.COMPENSATION_NODE_CLAIM_EXPIRED;
    public static final String COMPENSATION_NODE_CLAIM_RELEASED =
            CompensationEventTypes.COMPENSATION_NODE_CLAIM_RELEASED;
    public static final String COMPENSATION_NODE_CLAIM_SKIPPED =
            CompensationEventTypes.COMPENSATION_NODE_CLAIM_SKIPPED;
    public static final String COMPENSATION_NODE_COMPLETED = CompensationEventTypes.COMPENSATION_NODE_COMPLETED;
    public static final String COMPENSATION_NODE_FAILED = CompensationEventTypes.COMPENSATION_NODE_FAILED;
    public static final String COMPENSATION_NODE_SKIPPED = CompensationEventTypes.COMPENSATION_NODE_SKIPPED;
    public static final String COMPENSATION_COMPLETED = CompensationEventTypes.COMPENSATION_COMPLETED;
    public static final String COMPENSATION_FAILED = CompensationEventTypes.COMPENSATION_FAILED;

    private ExecutionEventTypes() {
    }
}
