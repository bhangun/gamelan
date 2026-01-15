package tech.kayys.silat.sdk.executor;

import java.util.List;

import io.smallrye.mutiny.Uni;
import tech.kayys.silat.execution.NodeExecutionResult;
import tech.kayys.silat.execution.NodeExecutionTask;

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
    default tech.kayys.silat.model.CommunicationType getCommunicationType() {
        return tech.kayys.silat.model.CommunicationType.UNSPECIFIED;
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
