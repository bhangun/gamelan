package tech.kayys.gamelan.scheduler;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.quarkus.runtime.Startup;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import tech.kayys.gamelan.engine.node.NodeExecutionTask;
import tech.kayys.gamelan.engine.executor.ExecutorInfo;
import tech.kayys.gamelan.registry.ExecutorRegistry;
import tech.kayys.gamelan.dispatcher.TaskDispatcherAggregator;

/**
 * Background worker that consumes tasks from the Task Queue and dispatches them to executors.
 */
@ApplicationScoped
@Startup
public class TaskWorker {

    private static final Logger LOG = LoggerFactory.getLogger(TaskWorker.class);

    @Inject
    TaskQueue taskQueue;

    @Inject
    ExecutorRegistry executorRegistry;

    @Inject
    TaskDispatcherAggregator taskDispatcher;

    @PostConstruct
    void start() {
        LOG.info("Starting Task Worker using {}...", taskQueue.getClass().getSimpleName());
        taskQueue.consume()
            .emitOn(Infrastructure.getDefaultWorkerPool())
            .onItem().transformToUniAndMerge(this::processTask)
            .subscribe().with(
                v -> {},
                err -> LOG.error("Error in Task Worker loop", err)
            );
    }

    private Uni<Void> processTask(TaskQueue.QueuedTask queuedTask) {
        NodeExecutionTask task = queuedTask.task();
        LOG.debug("Processing task: {} (run={})", task.nodeId().value(), task.runId().value());

        return findExecutor(task)
            .flatMap(executorOpt -> {
                if (executorOpt.isEmpty()) {
                    LOG.error("No executor found for task {}, will try again later or DLQ", task.nodeId().value());
                    return Uni.createFrom().voidItem();
                }

                ExecutorInfo executor = executorOpt.get();
                return taskDispatcher.dispatch(task, executor)
                    .flatMap(res -> taskQueue.acknowledge(queuedTask.messageId()))
                    .onFailure().invoke(err -> LOG.error("Failed to dispatch task {} to executor {}", 
                        task.nodeId().value(), executor.executorId(), err));
            })
            .onFailure().invoke(err -> LOG.error("Error processing queued task", err))
            .replaceWithVoid();
    }

    private Uni<Optional<ExecutorInfo>> findExecutor(NodeExecutionTask task) {
        return executorRegistry.getExecutorForNode(task.nodeId())
            .flatMap(initial -> {
                if (initial.isPresent()) return Uni.createFrom().item(initial);
                
                String nodeType = task.context() != null
                    ? String.valueOf(task.context().getOrDefault("__node_type__", ""))
                    : "";
                
                if (nodeType.isBlank()) return Uni.createFrom().item(initial);
                
                return executorRegistry.getExecutorsByType(nodeType)
                    .map(list -> (list == null || list.isEmpty()) 
                        ? Optional.<ExecutorInfo>empty() 
                        : Optional.of(list.get(0)));
            });
    }
}
