package tech.kayys.silat.dispatcher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import tech.kayys.silat.execution.NodeExecutionTask;
import tech.kayys.silat.model.ExecutorInfo;

@ApplicationScoped
public class TaskDispatcherAggregator {

    private static final Logger LOG = LoggerFactory.getLogger(TaskDispatcherAggregator.class);

    @Inject
    @jakarta.enterprise.inject.Any
    jakarta.enterprise.inject.Instance<TaskDispatcher> availableDispatchers;

    // List of all available dispatchers for dynamic resolution
    private volatile List<TaskDispatcher> allDispatchers;

    // private CustomCircuitBreaker<Void> circuitBreaker;

    @jakarta.annotation.PostConstruct
    void initCircuitBreaker() {
        /*
         * CircuitBreakerConfig config = CircuitBreakerConfig.builder()
         * .requestVolumeThreshold(5) // Minimum number of requests before circuit can
         * be opened
         * .failureRatio(0.5) // Failure ratio threshold
         * .delay(30000) // Delay in milliseconds before trying again
         * .build();
         * 
         * this.circuitBreaker = CustomCircuitBreaker.create(config);
         */
    }

    public Uni<Void> dispatch(NodeExecutionTask task, ExecutorInfo executor) {
        LOG.debug("Dispatching task run={}, node={} via {}",
                task.runId().value(),
                task.nodeId().value(),
                executor.communicationType());

        // Find the appropriate dispatcher based on support and priority
        TaskDispatcher selectedDispatcher = selectDispatcher(executor);

        if (selectedDispatcher == null) {
            LOG.error("No suitable dispatcher found for executor communication type: {}",
                    executor.communicationType());
            return Uni.createFrom().failure(
                    new IllegalArgumentException("No suitable dispatcher found for: " + executor.communicationType()));
        }

        LOG.debug("Using dispatcher {} for task run={}, node={}",
                selectedDispatcher.getClass().getSimpleName(),
                task.runId().value(),
                task.nodeId().value());

        // Apply circuit breaker to the dispatch operation
        return selectedDispatcher.dispatch(task, executor);
    }

    private TaskDispatcher selectDispatcher(ExecutorInfo executor) {
        // Initialize the list of dispatchers if not already done
        if (allDispatchers == null) {
            synchronized (this) {
                if (allDispatchers == null) {
                    List<TaskDispatcher> dispatchers = new ArrayList<>();
                    for (TaskDispatcher dispatcher : availableDispatchers) {
                        LOG.info("DefaultTaskDispatcher: Discovered dispatcher: {}", dispatcher.getClass().getName());
                        // Skip itself to avoid recursion if it's there
                        // if (dispatcher instanceof DefaultTaskDispatcher)
                        // continue;
                        // Actually, Instance iterable might return proxies.
                        // And checking instance of DefaultTaskDispatcher is safer to do by class
                        // comparison
                        if (dispatcher.getClass().getName().contains("DefaultTaskDispatcher")) {
                            continue;
                        }
                        dispatchers.add(dispatcher);
                    }

                    // Sort by priority (higher priority first)
                    dispatchers.sort(Comparator.comparingInt(TaskDispatcher::getPriority).reversed());

                    allDispatchers = Collections.unmodifiableList(dispatchers);
                }
            }
        }

        // Find the first dispatcher that supports this executor
        for (TaskDispatcher dispatcher : allDispatchers) {
            if (dispatcher.supports(executor)) {
                return dispatcher;
            }
        }
        return null;
    }
}
