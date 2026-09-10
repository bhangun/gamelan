package tech.kayys.gamelan.dispatcher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import tech.kayys.gamelan.engine.error.ErrorCode;
import tech.kayys.gamelan.engine.error.GamelanException;
import tech.kayys.gamelan.engine.executor.ExecutorInfo;
import tech.kayys.gamelan.engine.node.NodeExecutionTask;

@ApplicationScoped
public class TaskDispatcherAggregator implements TaskDispatcher {

    private static final Logger LOG = LoggerFactory.getLogger(TaskDispatcherAggregator.class);

    @Inject
    @jakarta.enterprise.inject.Any
    jakarta.enterprise.inject.Instance<TaskDispatcher> availableDispatchers;

    // List of all available dispatchers for dynamic resolution
    private volatile List<TaskDispatcher> allDispatchers;

    private final CopyOnWriteArrayList<TaskDispatcher> registeredDispatchers = new CopyOnWriteArrayList<>();

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

    @Override
    public Uni<Void> dispatch(NodeExecutionTask task, ExecutorInfo executor) {
        if (task == null) {
            return dispatchFailure(ErrorCode.DISPATCHER_INVALID_REQUEST, "NodeExecutionTask cannot be null");
        }
        if (executor == null) {
            return dispatchFailure(ErrorCode.DISPATCHER_INVALID_REQUEST, "ExecutorInfo cannot be null");
        }

        LOG.debug("Dispatching task run={}, node={} via {}",
                task.runId().value(),
                task.nodeId().value(),
                executor.communicationType());

        // Find the appropriate dispatcher based on support and priority
        Optional<TaskDispatcher> selectedDispatcher = selectDispatcher(executor);
        if (selectedDispatcher.isEmpty()) {
            LOG.error("No suitable dispatcher found for executor communication type: {}",
                    executor.communicationType());
            return dispatchFailure(
                    ErrorCode.DISPATCHER_NOT_FOUND,
                    "No suitable dispatcher found for executor "
                            + executor.executorId()
                            + " with communication type "
                            + executor.communicationType()
                            + ". Available dispatchers: "
                            + dispatcherNames(dispatchers()));
        }

        LOG.debug("Using dispatcher {} for task run={}, node={}",
                selectedDispatcher.get().getClass().getSimpleName(),
                task.runId().value(),
                task.nodeId().value());

        // Apply circuit breaker to the dispatch operation
        try {
            Uni<Void> dispatch = selectedDispatcher.get().dispatch(task, executor);
            if (dispatch == null) {
                return dispatchFailure(
                        ErrorCode.DISPATCHER_BAD_RESPONSE,
                        "Dispatcher " + selectedDispatcher.get().getClass().getName() + " returned null dispatch result");
            }
            return dispatch;
        } catch (GamelanException e) {
            return Uni.createFrom().failure(e);
        } catch (RuntimeException e) {
            return Uni.createFrom().failure(new TaskDispatchException(
                    "Dispatcher " + selectedDispatcher.get().getClass().getName() + " failed before dispatch completion",
                    e));
        }
    }

    @Override
    public boolean supports(ExecutorInfo executor) {
        return executor != null && selectDispatcher(executor).isPresent();
    }

    @Override
    public Uni<Boolean> isHealthy() {
        return Uni.createFrom().item(!dispatchers().isEmpty());
    }

    @Override
    public int getPriority() {
        return Integer.MIN_VALUE;
    }

    @Override
    public void registerDispatcher(TaskDispatcher dispatcher) {
        if (dispatcher == null) {
            throw new GamelanException(ErrorCode.DISPATCHER_INVALID_REQUEST, "TaskDispatcher cannot be null");
        }
        if (isSelf(dispatcher)) {
            throw new GamelanException(
                    ErrorCode.DISPATCHER_INVALID_REQUEST,
                    "TaskDispatcherAggregator cannot register itself");
        }
        registeredDispatchers.addIfAbsent(dispatcher);
        allDispatchers = null;
    }

    public List<TaskDispatcherCapability> capabilities() {
        return dispatchers().stream()
                .map(TaskDispatcherCapability::from)
                .toList();
    }

    private Optional<TaskDispatcher> selectDispatcher(ExecutorInfo executor) {
        for (TaskDispatcher dispatcher : dispatchers()) {
            if (supportsSafely(dispatcher, executor)) {
                return Optional.of(dispatcher);
            }
        }
        return Optional.empty();
    }

    private List<TaskDispatcher> dispatchers() {
        // Initialize the list of dispatchers if not already done
        if (allDispatchers == null) {
            synchronized (this) {
                if (allDispatchers == null) {
                    List<TaskDispatcher> dispatchers = new ArrayList<>();
                    if (availableDispatchers != null) {
                        for (TaskDispatcher dispatcher : availableDispatchers) {
                            if (isSelf(dispatcher)) {
                                continue;
                            }
                            LOG.info("Discovered task dispatcher: {}", dispatcher.getClass().getName());
                            addDispatcher(dispatchers, dispatcher);
                        }
                    }
                    registeredDispatchers.forEach(dispatcher -> addDispatcher(dispatchers, dispatcher));

                    // Sort by priority (higher priority first)
                    dispatchers.sort(Comparator.comparingInt(TaskDispatcher::getPriority).reversed());

                    allDispatchers = Collections.unmodifiableList(dispatchers);
                }
            }
        }
        return allDispatchers;
    }

    private static void addDispatcher(List<TaskDispatcher> dispatchers, TaskDispatcher dispatcher) {
        if (dispatcher != null && dispatchers.stream().noneMatch(existing -> existing == dispatcher)) {
            dispatchers.add(dispatcher);
        }
    }

    private static boolean supportsSafely(TaskDispatcher dispatcher, ExecutorInfo executor) {
        try {
            return dispatcher.supports(executor);
        } catch (RuntimeException e) {
            LOG.warn("Task dispatcher {} failed support check for executor {}: {}",
                    dispatcher.getClass().getName(),
                    executor != null ? executor.executorId() : "<null>",
                    e.toString());
            return false;
        }
    }

    private static boolean isSelf(TaskDispatcher dispatcher) {
        return dispatcher == null
                || dispatcher instanceof TaskDispatcherAggregator
                || dispatcher.getClass().getName().contains("DefaultTaskDispatcher");
    }

    private static String dispatcherNames(List<TaskDispatcher> dispatchers) {
        if (dispatchers.isEmpty()) {
            return "<none>";
        }
        return dispatchers.stream()
                .map(dispatcher -> dispatcher.getClass().getSimpleName())
                .toList()
                .toString();
    }

    public record TaskDispatcherCapability(
            String implementation,
            int priority) {

        private static TaskDispatcherCapability from(TaskDispatcher dispatcher) {
            return new TaskDispatcherCapability(
                    dispatcher.getClass().getName(),
                    dispatcher.getPriority());
        }
    }

    private static Uni<Void> dispatchFailure(ErrorCode errorCode, String message) {
        return Uni.createFrom().failure(new GamelanException(
                Objects.requireNonNull(errorCode, "ErrorCode cannot be null"),
                message));
    }
}
