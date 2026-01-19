package tech.kayys.gamelan.sdk.executor;

import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.sdk.executor.core.ExecutorTransport;
import tech.kayys.gamelan.sdk.executor.core.WorkflowExecutor;

/**
 * Extended interface for remote transports with registration/heartbeat
 */
public interface RemoteExecutorTransport extends ExecutorTransport {

    /**
     * Register executors with engine (REQUIRED for remote)
     */
    @Override
    Uni<Void> register(java.util.List<WorkflowExecutor> executors);

    /**
     * Unregister from engine (REQUIRED for remote)
     */
    @Override
    Uni<Void> unregister();

    /**
     * Send heartbeat (REQUIRED for remote)
     */
    @Override
    Uni<Void> sendHeartbeat();

    /**
     * Get heartbeat interval (REQUIRED for remote)
     */
    @Override
    java.time.Duration getHeartbeatInterval();
}