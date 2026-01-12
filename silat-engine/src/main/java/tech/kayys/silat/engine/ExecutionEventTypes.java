package tech.kayys.silat.engine;

public final class ExecutionEventTypes {

    static final String RUN_CREATED = "RUN_CREATED";
    static final String STATUS_CHANGED = "STATUS_CHANGED";
    static final String RUN_COMPLETED = "RUN_COMPLETED";
    static final String RUN_FAILED = "RUN_FAILED";
    static final String NODE_COMPLETED = "NODE_COMPLETED";
    static final String SIGNAL_RECEIVED = "SIGNAL_RECEIVED";

    private ExecutionEventTypes() {
    }
}
