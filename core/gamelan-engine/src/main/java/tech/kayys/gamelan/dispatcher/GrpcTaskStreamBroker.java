package tech.kayys.gamelan.dispatcher;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.engine.node.NodeExecutionTask;

/**
 * Broker for gRPC pull/stream task delivery.
 *
 * Push transports dispatch directly to an executor endpoint. Stream transports
 * assign tasks to an executor inbox, then the executor pulls them through
 * ExecutorService.StreamTasks.
 */
public interface GrpcTaskStreamBroker {

    Uni<Void> assign(String executorId, NodeExecutionTask task);

    Multi<StreamedTask> stream(String executorId, int maxConcurrent);

    /**
     * Marks a streamed task as accepted by an executor.
     *
     * <p>ACK is not completion. Brokers should keep the task recoverable until
     * {@link #complete(String)} is called after the engine durably processes the
     * result. In-memory brokers use ACK to avoid immediate redelivery on normal
     * stream reconnects; durable brokers can use it as lease/activity metadata.
     */
    Uni<Void> acknowledge(String taskId);

    Uni<Void> complete(String taskId);

    static String taskId(NodeExecutionTask task) {
        return task.taskId();
    }

    record StreamedTask(String taskId, NodeExecutionTask task) {
    }
}
