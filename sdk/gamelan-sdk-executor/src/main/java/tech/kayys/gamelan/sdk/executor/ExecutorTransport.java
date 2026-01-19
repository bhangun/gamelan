package tech.kayys.gamelan.sdk.executor;

import java.util.List;

import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.engine.node.NodeExecutionResult;
import tech.kayys.gamelan.engine.node.NodeExecutionTask;

/**
 * Transport interface for executor communication
 */
public interface ExecutorTransport {

    /**
     * Register executors with engine
     */
    Uni<Void> register(List<WorkflowExecutor> executors);

    /**
     * Unregister from engine
     */
    Uni<Void> unregister();

    /**
     * Receive tasks from engine (streaming)
     */
    io.smallrye.mutiny.Multi<NodeExecutionTask> receiveTasks();

    /**
     * Send task result to engine
     */
    Uni<Void> sendResult(NodeExecutionResult result);

    /**
     * Send heartbeat
     */
    Uni<Void> sendHeartbeat();

    /**
     * Get the communication type of this transport
     */
    default tech.kayys.gamelan.engine.protocol.CommunicationType getCommunicationType() {
        return tech.kayys.gamelan.engine.protocol.CommunicationType.UNSPECIFIED;
    }

    /**
     * Get configured heartbeat interval
     * 
     * @return Duration interval
     */
    default java.time.Duration getHeartbeatInterval() {
        return java.time.Duration.ofSeconds(30);
    }
}
