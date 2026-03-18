package tech.kayys.gamelan.scheduler;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.engine.node.NodeExecutionTask;

public interface TaskQueue {

    Uni<Void> enqueue(NodeExecutionTask task);

    Multi<QueuedTask> consume();

    Uni<Void> acknowledge(String messageId);

    static record QueuedTask(String messageId, NodeExecutionTask task) {}
}
