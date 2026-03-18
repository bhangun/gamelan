package tech.kayys.gamelan.scheduler;

import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.quarkus.arc.properties.IfBuildProperty;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.operators.multi.processors.UnicastProcessor;
import jakarta.enterprise.context.ApplicationScoped;

import tech.kayys.gamelan.engine.node.NodeExecutionTask;

/**
 * In-memory Task Queue for standalone/local mode
 */
@ApplicationScoped
@IfBuildProperty(name = "gamelan.scheduler.mode", stringValue = "local", stringValueIfMissing = "local")
public class InMemoryTaskQueue implements TaskQueue {

    private static final Logger LOG = LoggerFactory.getLogger(InMemoryTaskQueue.class);
    
    private final UnicastProcessor<QueuedTask> processor = UnicastProcessor.create();
    private final AtomicLong counter = new AtomicLong(0);

    @Override
    public Uni<Void> enqueue(NodeExecutionTask task) {
        String messageId = String.valueOf(counter.incrementAndGet());
        LOG.debug("Enqueuing task in-memory: {} (ID: {})", task.nodeId().value(), messageId);
        processor.onNext(new QueuedTask(messageId, task));
        return Uni.createFrom().voidItem();
    }

    @Override
    public Multi<QueuedTask> consume() {
        return processor;
    }

    @Override
    public Uni<Void> acknowledge(String messageId) {
        // No-op for in-memory simple queue
        return Uni.createFrom().voidItem();
    }
}
