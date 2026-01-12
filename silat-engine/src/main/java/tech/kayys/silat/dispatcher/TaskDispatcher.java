package tech.kayys.silat.dispatcher;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import tech.kayys.silat.execution.NodeExecutionTask;
import tech.kayys.silat.model.ExecutorInfo;

/**
 * Dispatches tasks to appropriate executors
 */
@ApplicationScoped
public class TaskDispatcher {

    private static final Logger LOG = LoggerFactory.getLogger(TaskDispatcher.class);

    @Inject
    GrpcTaskDispatcher grpc;
    @Inject
    KafkaTaskDispatcher kafka;
    @Inject
    RestTaskDispatcher rest;

    public Uni<Void> dispatch(NodeExecutionTask task, ExecutorInfo executor) {
        LOG.debug("Dispatching task run={}, node={} via {}",
                task.runId().value(),
                task.nodeId().value(),
                executor.communicationType());

        return switch (executor.communicationType()) {
            case GRPC -> grpc.dispatch(task, executor);
            case KAFKA -> kafka.dispatch(task, executor);
            case REST -> rest.dispatch(task, executor);
        };
    }
}
