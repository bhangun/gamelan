package tech.kayys.gamelan.engine;

public final class ExecutionEventTypes {

    public static final String RUN_CREATED = "RUN_CREATED";
    public static final String STATUS_CHANGED = "STATUS_CHANGED";
    public static final String RUN_COMPLETED = "RUN_COMPLETED";
    public static final String RUN_FAILED = "RUN_FAILED";
    public static final String NODE_COMPLETED = "NODE_COMPLETED";
    public static final String SIGNAL_RECEIVED = "SIGNAL_RECEIVED";

    private ExecutionEventTypes() {
    }
}
