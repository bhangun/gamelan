package tech.kayys.gamelan.sdk.executor.core;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.engine.node.NodeExecutionResult;
import tech.kayys.gamelan.engine.node.NodeExecutionTask;
import tech.kayys.gamelan.engine.protocol.CommunicationType;

/**
 * Common transport interface for all executors
 */
public interface ExecutorTransport {

    /**
     * Get the communication type of this transport
     */
    CommunicationType getCommunicationType();

    /**
     * Receive tasks from engine (streaming)
     */
    Multi<NodeExecutionTask> receiveTasks();

    /**
     * Send task result to engine
     */
    Uni<Void> sendResult(NodeExecutionResult result);

    /**
     * Optional: Get configured heartbeat interval
     * Default implementation returns null for transports without heartbeat
     */
    default java.time.Duration getHeartbeatInterval() {
        return null;
    }

    /**
     * Optional: Send heartbeat (only for remote transports)
     * Default no-op for local transports
     */
    default Uni<Void> sendHeartbeat() {
        return Uni.createFrom().voidItem();
    }

    /**
     * Optional: Register executors (only for remote transports)
     * Default no-op for local transports
     */
    default Uni<Void> register(java.util.List<WorkflowExecutor> executors) {
        return Uni.createFrom().voidItem();
    }

    /**
     * Optional: Unregister from engine (only for remote transports)
     * Default no-op for local transports
     */
    default Uni<Void> unregister() {
        return Uni.createFrom().voidItem();
    }
}