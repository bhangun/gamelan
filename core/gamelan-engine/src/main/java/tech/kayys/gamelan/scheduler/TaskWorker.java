package tech.kayys.gamelan.scheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.quarkus.runtime.Startup;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import io.smallrye.mutiny.subscription.Cancellable;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import tech.kayys.gamelan.engine.node.NodeExecutionTask;
import tech.kayys.gamelan.engine.executor.ExecutorInfo;
import tech.kayys.gamelan.engine.executor.ExecutorPlacementRequirements;
import tech.kayys.gamelan.registry.ExecutorRegistryService;
import tech.kayys.gamelan.registry.ExecutorSelectionRequest;
import tech.kayys.gamelan.registry.ExecutorSelectionReport;
import tech.kayys.gamelan.dispatcher.TaskDispatcherAggregator;

/**
 * Background worker that consumes tasks from the Task Queue and dispatches them to executors.
 */
@ApplicationScoped
@Startup
public class TaskWorker {

    private static final Logger LOG = LoggerFactory.getLogger(TaskWorker.class);
    private static final int DEFAULT_MAX_CONCURRENT_TASKS = 64;
    private static final int DEFAULT_MAX_DELIVERY_ATTEMPTS = 100;
    private static final Duration DEFAULT_LEASE_RENEWAL_INTERVAL = Duration.ofSeconds(10);
    private static final Duration DEFAULT_LEASE_RENEWAL_DURATION = Duration.ofSeconds(30);
    private static final Duration DEFAULT_CONSUME_RETRY_INITIAL_BACKOFF = Duration.ofSeconds(1);
    private static final Duration DEFAULT_CONSUME_RETRY_MAX_BACKOFF = Duration.ofSeconds(30);
    private static final Duration DEFAULT_DISPATCH_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration DEFAULT_SHUTDOWN_GRACE_PERIOD = Duration.ofSeconds(30);
    private static final String DIAGNOSTIC_DEAD_LETTER_REASON = "deadLetterReason";
    private static final String DIAGNOSTIC_MAX_DELIVERY_ATTEMPTS = "maxDeliveryAttempts";
    private static final String DIAGNOSTIC_DELIVERY_BUDGET_EXHAUSTED = "deliveryBudgetExhausted";
    private static final String DIAGNOSTIC_LEASE_ID = "leaseId";
    private static final String DIAGNOSTIC_LEASE_EXPIRES_AT = "leaseExpiresAt";

    @Inject
    TaskQueue taskQueue;

    @Inject
    ExecutorRegistryService executorRegistry;

    @Inject
    TaskDispatcherAggregator taskDispatcher;

    @Inject
    TaskDeadLetterQueue deadLetterQueue;

    @ConfigProperty(name = "gamelan.task-worker.no-executor.defer-delay", defaultValue = "1s")
    Duration noExecutorDeferDelay;

    @ConfigProperty(name = "gamelan.task-worker.no-executor.max-defers", defaultValue = "30")
    int maxNoExecutorDefers;

    @ConfigProperty(name = "gamelan.task-worker.max-concurrent-tasks", defaultValue = "64")
    int maxConcurrentTasks = DEFAULT_MAX_CONCURRENT_TASKS;

    @ConfigProperty(name = "gamelan.task-worker.max-delivery-attempts", defaultValue = "100")
    int maxDeliveryAttempts = DEFAULT_MAX_DELIVERY_ATTEMPTS;

    @ConfigProperty(name = "gamelan.task-worker.lease-renewal.enabled", defaultValue = "true")
    boolean leaseRenewalEnabled = true;

    @ConfigProperty(name = "gamelan.task-worker.lease-renewal.interval", defaultValue = "10s")
    Duration leaseRenewalInterval = DEFAULT_LEASE_RENEWAL_INTERVAL;

    @ConfigProperty(name = "gamelan.task-worker.lease-renewal.duration", defaultValue = "30s")
    Duration leaseRenewalDuration = DEFAULT_LEASE_RENEWAL_DURATION;

    @ConfigProperty(name = "gamelan.task-worker.consume-retry.initial-backoff", defaultValue = "1s")
    Duration consumeRetryInitialBackoff = DEFAULT_CONSUME_RETRY_INITIAL_BACKOFF;

    @ConfigProperty(name = "gamelan.task-worker.consume-retry.max-backoff", defaultValue = "30s")
    Duration consumeRetryMaxBackoff = DEFAULT_CONSUME_RETRY_MAX_BACKOFF;

    @ConfigProperty(name = "gamelan.task-worker.dispatch-timeout", defaultValue = "5m")
    Duration dispatchTimeout = DEFAULT_DISPATCH_TIMEOUT;

    @ConfigProperty(name = "gamelan.task-worker.shutdown-grace-period", defaultValue = "30s")
    Duration shutdownGracePeriod = DEFAULT_SHUTDOWN_GRACE_PERIOD;

    private final Object inFlightMonitor = new Object();
    private final AtomicBoolean stopping = new AtomicBoolean();
    private final AtomicLong acceptedTaskCount = new AtomicLong();
    private final AtomicLong finishedTaskCount = new AtomicLong();
    private final AtomicLong failedTaskCount = new AtomicLong();
    private final AtomicLong skippedDueToStoppingCount = new AtomicLong();
    private final AtomicLong queueStreamFailureCount = new AtomicLong();
    private final AtomicBoolean operatorPaused = new AtomicBoolean();
    private final ConcurrentMap<String, InFlightTask> inFlightTasks = new ConcurrentHashMap<>();
    private final AtomicBoolean workerLoopActive = new AtomicBoolean();
    private volatile String lastTaskFailure;
    private volatile Instant lastTaskFailureAt;
    private volatile String lastQueueStreamFailure;
    private volatile Instant lastQueueStreamFailureAt;
    private volatile ScheduledExecutorService leaseRenewalScheduler;
    private volatile Cancellable workerSubscription;

    @PostConstruct
    void start() {
        int concurrency = effectiveMaxConcurrentTasks();
        LOG.info("Starting Task Worker using {} with maxConcurrentTasks={}...",
                taskQueue.getClass().getSimpleName(),
                concurrency);
        subscribeWorker(taskQueue::consume, concurrency);
    }

    Cancellable subscribeWorker(
            Supplier<Multi<TaskQueue.QueuedTask>> queuedTasks,
            int concurrency) {
        stopWorkerSubscription();
        stopping.set(false);
        operatorPaused.set(false);
        workerLoopActive.set(true);
        Cancellable subscription = supervisedTaskStream(queuedTasks, concurrency)
                .onTermination().invoke(() -> workerLoopActive.set(false))
                .subscribe().with(
                        v -> {},
                        err -> LOG.error("Task Worker loop stopped unexpectedly", err)
                );
        workerSubscription = subscription;
        return subscription;
    }

    Multi<Void> supervisedTaskStream(
            Supplier<Multi<TaskQueue.QueuedTask>> queuedTasks,
            int concurrency) {
        Objects.requireNonNull(queuedTasks, "queuedTasks cannot be null");
        Duration initialBackoff = positiveDuration(
                consumeRetryInitialBackoff,
                DEFAULT_CONSUME_RETRY_INITIAL_BACKOFF);
        Duration maxBackoff = maxDuration(
                positiveDuration(consumeRetryMaxBackoff, DEFAULT_CONSUME_RETRY_MAX_BACKOFF),
                initialBackoff);
        return Multi.createFrom().deferred(() -> processTasks(queuedTasks.get(), concurrency))
                .onFailure().invoke(error -> {
                    recordQueueStreamFailure(error);
                    LOG.warn("Task Worker queue stream failed; retrying consume subscription with backoff {}..{}: {}",
                            initialBackoff,
                            maxBackoff,
                            errorSummary(error));
                    LOG.debug("Task Worker queue stream failure", error);
                })
                .onFailure().retry()
                .withBackOff(initialBackoff, maxBackoff)
                .indefinitely();
    }

    Multi<Void> processTasks(Multi<TaskQueue.QueuedTask> queuedTasks, int concurrency) {
        Objects.requireNonNull(queuedTasks, "queuedTasks cannot be null");
        return queuedTasks
            .emitOn(Infrastructure.getDefaultWorkerPool())
            .onItem().transformToUni(this::processTask)
            .merge(effectiveConcurrency(concurrency));
    }

    @PreDestroy
    void stop() {
        operatorPaused.set(false);
        stopping.set(true);
        stopWorkerSubscription();
        awaitInFlightDrain();
        stopLeaseRenewalScheduler();
    }

    public WorkerControlResult pause() {
        operatorPaused.set(true);
        stopping.set(true);
        stopWorkerSubscription();
        return controlResult(
                WorkerControlAction.PAUSE,
                true,
                true,
                "worker-paused");
    }

    public WorkerControlResult drain() {
        operatorPaused.set(true);
        stopping.set(true);
        stopWorkerSubscription();
        awaitInFlightDrain();
        boolean drained = inFlightTasks.isEmpty();
        return controlResult(
                WorkerControlAction.DRAIN,
                true,
                drained,
                drained ? "worker-drained" : "drain-timeout");
    }

    public WorkerControlResult resume() {
        if (!inFlightTasks.isEmpty()) {
            return controlResult(
                    WorkerControlAction.RESUME,
                    false,
                    false,
                    "in-flight-tasks-active");
        }
        if (taskQueue == null) {
            return controlResult(
                    WorkerControlAction.RESUME,
                    false,
                    false,
                    "task-queue-unavailable");
        }
        if (workerSubscription != null && workerLoopActive.get() && !stopping.get()) {
            return controlResult(
                    WorkerControlAction.RESUME,
                    true,
                    true,
                    "worker-already-running");
        }
        subscribeWorker(taskQueue::consume, effectiveMaxConcurrentTasks());
        return controlResult(
                WorkerControlAction.RESUME,
                true,
                true,
                "worker-resumed");
    }

    void stopWorkerSubscription() {
        Cancellable subscription = workerSubscription;
        if (subscription != null) {
            subscription.cancel();
            workerSubscription = null;
        }
        workerLoopActive.set(false);
    }

    void stopLeaseRenewalScheduler() {
        ScheduledExecutorService scheduler = leaseRenewalScheduler;
        if (scheduler != null) {
            scheduler.shutdownNow();
            leaseRenewalScheduler = null;
        }
    }

    Uni<Void> processTask(TaskQueue.QueuedTask queuedTask) {
        return Uni.createFrom().emitter(emitter -> {
            if (stopping.get()) {
                skippedDueToStoppingCount.incrementAndGet();
                LOG.debug("Skipping queued task message={} leaseId={} because Task Worker is stopping",
                        safeMessageId(queuedTask),
                        safeLeaseId(queuedTask));
                emitter.complete(null);
                return;
            }

            InFlightTask inFlightTask = registerInFlight(queuedTask);
            Uni.createFrom().deferred(() -> processTaskInternal(queuedTask))
                    .onFailure().invoke(err -> {
                        recordTaskFailure(err);
                        LOG.error("Error processing queued task message={} leaseId={}",
                                safeMessageId(queuedTask),
                                safeLeaseId(queuedTask),
                                err);
                    })
                    .onFailure().recoverWithNull()
                    .onTermination().invoke(() -> completeInFlight(inFlightTask))
                    .subscribe().with(
                            ignored -> emitter.complete(null),
                            err -> {
                                LOG.error("Unexpected unrecovered queued task failure message={} leaseId={}",
                                        safeMessageId(queuedTask),
                                        safeLeaseId(queuedTask),
                                        err);
                                emitter.complete(null);
                            });
        });
    }

    void awaitInFlightDrain() {
        Duration gracePeriod = shutdownGracePeriod();
        if (inFlightTasks.isEmpty()) {
            return;
        }

        if (gracePeriod.isZero()) {
            LOG.warn("Task Worker shutdown has {} in-flight task(s) and no grace period: {}",
                    inFlightTasks.size(),
                    inFlightTaskSummary());
            return;
        }

        long deadlineNanos = System.nanoTime() + Math.max(1L, gracePeriod.toNanos());
        synchronized (inFlightMonitor) {
            while (!inFlightTasks.isEmpty()) {
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0) {
                    LOG.warn("Task Worker shutdown grace period {} elapsed with {} in-flight task(s): {}",
                            gracePeriod,
                            inFlightTasks.size(),
                            inFlightTaskSummary());
                    return;
                }
                try {
                    long millis = TimeUnit.NANOSECONDS.toMillis(remainingNanos);
                    int nanos = (int) (remainingNanos - TimeUnit.MILLISECONDS.toNanos(millis));
                    inFlightMonitor.wait(millis, nanos);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    LOG.warn("Interrupted while waiting for Task Worker in-flight drain; {} task(s) still active: {}",
                            inFlightTasks.size(),
                            inFlightTaskSummary());
                    return;
                }
            }
        }
    }

    int inFlightTaskCount() {
        return inFlightTasks.size();
    }

    public WorkerStatus status() {
        Instant observedAt = Instant.now();
        int inFlightCount = inFlightTasks.size();
        int maxConcurrent = effectiveMaxConcurrentTasks();
        boolean subscriptionActive = workerSubscription != null && workerLoopActive.get();
        boolean isStopping = stopping.get();
        boolean isOperatorPaused = operatorPaused.get();
        WorkerState state = workerState(subscriptionActive, isStopping, isOperatorPaused, inFlightCount);
        return new WorkerStatus(
                state,
                state == WorkerState.RUNNING,
                isStopping,
                isOperatorPaused,
                subscriptionActive,
                implementationName(taskQueue),
                implementationName(taskDispatcher),
                maxConcurrent,
                inFlightCount,
                Math.max(0, maxConcurrent - inFlightCount),
                leaseRenewalEnabled,
                leaseRenewalSchedulerRunning(),
                durationText(noExecutorDeferDelay),
                maxNoExecutorDefers,
                effectiveMaxDeliveryAttempts(),
                durationText(dispatchTimeout()),
                durationText(shutdownGracePeriod()),
                durationText(positiveDuration(leaseRenewalInterval, DEFAULT_LEASE_RENEWAL_INTERVAL)),
                durationText(positiveDuration(leaseRenewalDuration, DEFAULT_LEASE_RENEWAL_DURATION)),
                durationText(positiveDuration(consumeRetryInitialBackoff, DEFAULT_CONSUME_RETRY_INITIAL_BACKOFF)),
                durationText(maxDuration(
                        positiveDuration(consumeRetryMaxBackoff, DEFAULT_CONSUME_RETRY_MAX_BACKOFF),
                        positiveDuration(consumeRetryInitialBackoff, DEFAULT_CONSUME_RETRY_INITIAL_BACKOFF))),
                acceptedTaskCount.get(),
                finishedTaskCount.get(),
                failedTaskCount.get(),
                skippedDueToStoppingCount.get(),
                queueStreamFailureCount.get(),
                lastTaskFailure,
                lastTaskFailureAt,
                lastQueueStreamFailure,
                lastQueueStreamFailureAt,
                observedAt,
                inFlightTaskStatuses(observedAt, 10));
    }

    private Uni<Void> processTaskInternal(TaskQueue.QueuedTask queuedTask) {
        Objects.requireNonNull(queuedTask, "queuedTask cannot be null");
        NodeExecutionTask task = queuedTask.task();
        LOG.debug("Processing task: {} (run={})", task.nodeId().value(), task.runId().value());

        int effectiveMaxDeliveryAttempts = effectiveMaxDeliveryAttempts();
        if (queuedTask.deliveryAttempt() > effectiveMaxDeliveryAttempts) {
            LOG.error("Task {} exceeded delivery-attempt budget, dead-lettering message {} deliveryAttempt={} maxDeliveryAttempts={} deferCount={}",
                    task.nodeId().value(),
                    queuedTask.messageId(),
                    queuedTask.deliveryAttempt(),
                    effectiveMaxDeliveryAttempts,
                    queuedTask.deferCount());
            return deadLetterDeliveryBudgetExceeded(queuedTask, effectiveMaxDeliveryAttempts)
                    .flatMap(ignored -> taskQueue.acknowledge(queuedTask))
                    .onFailure().invoke(err -> LOG.error("Error processing queued task", err))
                    .replaceWithVoid();
        }

        return selectExecutor(task)
            .flatMap(report -> {
                if (report.selectedExecutor().isEmpty()) {
                    NoExecutorTaskDecision decision = NoExecutorTaskDecision.evaluate(
                            queuedTask,
                            report,
                            maxNoExecutorDefers);
                    if (decision.shouldDeadLetter()) {
                        LOG.error("No executor found for task {}, dead-lettering message {} reason={} deferCount={} maxDefers={} permanent={} selection={}",
                                task.nodeId().value(),
                                queuedTask.messageId(),
                                decision.reason(),
                                decision.deferCount(),
                                decision.maxDefers(),
                                decision.permanentSelectionFailure(),
                                report.toErrorContext());
                        return deadLetter(queuedTask, decision, report)
                                .flatMap(ignored -> taskQueue.acknowledge(queuedTask));
                    }
                    LOG.warn("No executor found for task {}, deferring message {} by {} reason={} deferCount={} maxDefers={} selection={}",
                            task.nodeId().value(),
                            queuedTask.messageId(),
                            noExecutorDeferDelay,
                            decision.reason(),
                            decision.deferCount(),
                            decision.maxDefers(),
                            report.toErrorContext());
                    return taskQueue.defer(queuedTask, noExecutorDeferDelay, decision.reason());
                }

                ExecutorInfo executor = report.selectedExecutor().get();
                return dispatchWithLeaseRenewal(queuedTask, task, executor)
                    .flatMap(res -> taskQueue.acknowledge(queuedTask))
                    .onFailure().invoke(err -> LOG.error("Failed to dispatch task {} to executor {}",
                        task.nodeId().value(), executor.executorId(), err));
            })
            .replaceWithVoid();
    }

    private Uni<ExecutorSelectionReport> selectExecutor(NodeExecutionTask task) {
        ExecutorPlacementRequirements placement = ExecutorPlacementRequirements.fromContext(
                task.collaborationContext());
        Map<String, Object> selectionContext = executorSelectionContext(task);
        String nodeType = nodeType(task);

        if (isSpecificExecutorType(nodeType)) {
            return executorRegistry.selectExecutorWithDiagnostics(ExecutorSelectionRequest.forNodeType(
                    task.nodeId(),
                    nodeType,
                    placement,
                    selectionContext));
        }

        return executorRegistry.selectExecutorWithDiagnostics(ExecutorSelectionRequest.forNode(
                task.nodeId(),
                placement,
                selectionContext));
    }

    private Uni<Void> deadLetter(
            TaskQueue.QueuedTask queuedTask,
            NoExecutorTaskDecision decision,
            ExecutorSelectionReport report) {
        TaskDeadLetterQueue.DeadLetterTask deadLetter = new TaskDeadLetterQueue.DeadLetterTask(
                queuedTask.messageId(),
                queuedTask.task(),
                decision.reason(),
                queuedTask.deliveryAttempt(),
                queuedTask.deferCount(),
                queuedTask.firstSeenAt(),
                Instant.now(),
                decision.diagnostics(report));
        return deadLetterQueue.publish(deadLetter);
    }

    private Uni<Void> dispatchWithLeaseRenewal(
            TaskQueue.QueuedTask queuedTask,
            NodeExecutionTask task,
            ExecutorInfo executor) {
        LeaseRenewal leaseRenewal = startLeaseRenewal(queuedTask);
        try {
            Uni<Void> dispatch = taskDispatcher.dispatch(task, executor);
            if (dispatch == null) {
                return Uni.createFrom().failure(new IllegalStateException(
                        "Task dispatcher returned null dispatch result"));
            }
            return withDispatchTimeout(dispatch, queuedTask, executor)
                    .onTermination().invoke(leaseRenewal::stop);
        } catch (RuntimeException error) {
            leaseRenewal.stop();
            return Uni.createFrom().failure(error);
        }
    }

    private Uni<Void> withDispatchTimeout(
            Uni<Void> dispatch,
            TaskQueue.QueuedTask queuedTask,
            ExecutorInfo executor) {
        Duration timeout = dispatchTimeout();
        if (timeout == null) {
            return dispatch;
        }
        return dispatch.ifNoItem().after(timeout).failWith(() -> new TaskDispatchTimeoutException(
                "Task dispatch timed out after " + timeout
                        + " for message " + safeMessageId(queuedTask)
                        + " executor=" + (executor != null ? executor.executorId() : "<null>")));
    }

    private LeaseRenewal startLeaseRenewal(TaskQueue.QueuedTask queuedTask) {
        if (!leaseRenewalEnabled || queuedTask == null || !queuedTask.hasLeaseExpiry()) {
            return LeaseRenewal.noop();
        }
        Duration interval = positiveDuration(leaseRenewalInterval, DEFAULT_LEASE_RENEWAL_INTERVAL);
        Duration duration = positiveDuration(leaseRenewalDuration, DEFAULT_LEASE_RENEWAL_DURATION);
        AtomicBoolean stopped = new AtomicBoolean();
        long intervalMillis = Math.max(1L, interval.toMillis());
        ScheduledFuture<?> future = leaseRenewalScheduler().scheduleAtFixedRate(
                () -> renewLease(queuedTask, duration, stopped),
                intervalMillis,
                intervalMillis,
                TimeUnit.MILLISECONDS);
        return new LeaseRenewal(stopped, future);
    }

    private void renewLease(
            TaskQueue.QueuedTask queuedTask,
            Duration duration,
            AtomicBoolean stopped) {
        if (stopped.get()) {
            return;
        }
        taskQueue.renewLease(queuedTask, duration)
                .subscribe().with(
                        renewed -> {
                            if (!Boolean.TRUE.equals(renewed) && stopped.compareAndSet(false, true)) {
                                LOG.warn("Task queue lease renewal was rejected for message {} leaseId={}; task may be redelivered before this worker finishes",
                                        queuedTask.messageId(),
                                        queuedTask.leaseId());
                            }
                        },
                        error -> {
                            if (stopped.compareAndSet(false, true)) {
                                LOG.warn("Task queue lease renewal failed for message {} leaseId={}: {}",
                                        queuedTask.messageId(),
                                        queuedTask.leaseId(),
                                        errorSummary(error));
                                LOG.debug("Task queue lease renewal failure", error);
                            }
                        });
    }

    private ScheduledExecutorService leaseRenewalScheduler() {
        ScheduledExecutorService scheduler = leaseRenewalScheduler;
        if (scheduler == null) {
            synchronized (this) {
                scheduler = leaseRenewalScheduler;
                if (scheduler == null) {
                    scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                        Thread thread = new Thread(r, "gamelan-task-worker-lease-renewal");
                        thread.setDaemon(true);
                        return thread;
                    });
                    leaseRenewalScheduler = scheduler;
                }
            }
        }
        return scheduler;
    }

    private static Duration positiveDuration(Duration value, Duration fallback) {
        Duration effectiveFallback = Objects.requireNonNull(fallback, "fallback cannot be null");
        return value != null && !value.isZero() && !value.isNegative()
                ? value
                : effectiveFallback;
    }

    private InFlightTask registerInFlight(TaskQueue.QueuedTask queuedTask) {
        InFlightTask task = InFlightTask.from(queuedTask, acceptedTaskCount.incrementAndGet());
        inFlightTasks.put(task.key(), task);
        return task;
    }

    private void completeInFlight(InFlightTask task) {
        if (task == null) {
            return;
        }
        inFlightTasks.remove(task.key());
        finishedTaskCount.incrementAndGet();
        synchronized (inFlightMonitor) {
            inFlightMonitor.notifyAll();
        }
    }

    private void recordTaskFailure(Throwable error) {
        failedTaskCount.incrementAndGet();
        lastTaskFailure = errorSummary(error);
        lastTaskFailureAt = Instant.now();
    }

    private void recordQueueStreamFailure(Throwable error) {
        queueStreamFailureCount.incrementAndGet();
        lastQueueStreamFailure = errorSummary(error);
        lastQueueStreamFailureAt = Instant.now();
    }

    private Duration shutdownGracePeriod() {
        return shutdownGracePeriod != null && !shutdownGracePeriod.isNegative()
                ? shutdownGracePeriod
                : DEFAULT_SHUTDOWN_GRACE_PERIOD;
    }

    private boolean leaseRenewalSchedulerRunning() {
        ScheduledExecutorService scheduler = leaseRenewalScheduler;
        return scheduler != null && !scheduler.isShutdown();
    }

    private Duration dispatchTimeout() {
        return dispatchTimeout != null && !dispatchTimeout.isZero() && !dispatchTimeout.isNegative()
                ? dispatchTimeout
                : null;
    }

    private static Duration maxDuration(Duration first, Duration second) {
        return first.compareTo(second) >= 0 ? first : second;
    }

    private static String errorSummary(Throwable error) {
        String message = error != null ? error.getMessage() : null;
        return message == null || message.isBlank()
                ? (error != null ? error.getClass().getSimpleName() : "unknown")
                : error.getClass().getSimpleName() + ": " + message.replaceAll("\\s+", " ").trim();
    }

    private static String safeMessageId(TaskQueue.QueuedTask queuedTask) {
        return queuedTask != null ? queuedTask.messageId() : "<null>";
    }

    private static String safeLeaseId(TaskQueue.QueuedTask queuedTask) {
        return queuedTask != null ? queuedTask.leaseId() : "<null>";
    }

    private String inFlightTaskSummary() {
        StringBuilder summary = new StringBuilder();
        int count = 0;
        for (InFlightTask task : inFlightTasks.values()) {
            if (count > 0) {
                summary.append(", ");
            }
            if (count >= 10) {
                summary.append("...");
                break;
            }
            summary.append(task.summary());
            count++;
        }
        return summary.toString();
    }

    private List<InFlightTaskStatus> inFlightTaskStatuses(Instant observedAt, int limit) {
        return inFlightTasks.values().stream()
                .sorted((first, second) -> first.startedAt().compareTo(second.startedAt()))
                .limit(Math.max(0, limit))
                .map(task -> task.status(observedAt))
                .toList();
    }

    private static WorkerState workerState(
            boolean subscriptionActive,
            boolean stopping,
            boolean operatorPaused,
            int inFlightCount) {
        if ((stopping || !subscriptionActive) && inFlightCount > 0) {
            return WorkerState.DRAINING;
        }
        if (operatorPaused && stopping) {
            return WorkerState.PAUSED;
        }
        if (subscriptionActive && !stopping) {
            return WorkerState.RUNNING;
        }
        return WorkerState.STOPPED;
    }

    private WorkerControlResult controlResult(
            WorkerControlAction action,
            boolean accepted,
            boolean completed,
            String reason) {
        return new WorkerControlResult(action, accepted, completed, reason, status(), Instant.now());
    }

    private static String implementationName(Object component) {
        return component != null ? component.getClass().getSimpleName() : "<unavailable>";
    }

    private static String durationText(Duration duration) {
        return duration != null ? duration.toString() : null;
    }

    private Uni<Void> deadLetterDeliveryBudgetExceeded(
            TaskQueue.QueuedTask queuedTask,
            int effectiveMaxDeliveryAttempts) {
        TaskDeadLetterQueue.DeadLetterTask deadLetter = new TaskDeadLetterQueue.DeadLetterTask(
                queuedTask.messageId(),
                queuedTask.task(),
                TaskDeadLetterReasons.MAX_DELIVERY_ATTEMPTS_EXCEEDED,
                queuedTask.deliveryAttempt(),
                queuedTask.deferCount(),
                queuedTask.firstSeenAt(),
                Instant.now(),
                deliveryBudgetDiagnostics(queuedTask, effectiveMaxDeliveryAttempts));
        return deadLetterQueue.publish(deadLetter);
    }

    private Map<String, Object> deliveryBudgetDiagnostics(
            TaskQueue.QueuedTask queuedTask,
            int effectiveMaxDeliveryAttempts) {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put(NoExecutorTaskDecision.DIAGNOSTIC_WORKER_DECISION,
                NoExecutorTaskDecision.ACTION_DEAD_LETTER);
        diagnostics.put(DIAGNOSTIC_DEAD_LETTER_REASON,
                TaskDeadLetterReasons.MAX_DELIVERY_ATTEMPTS_EXCEEDED);
        diagnostics.put(NoExecutorTaskDecision.DIAGNOSTIC_DELIVERY_ATTEMPT,
                queuedTask.deliveryAttempt());
        diagnostics.put(NoExecutorTaskDecision.DIAGNOSTIC_DEFER_COUNT,
                queuedTask.deferCount());
        diagnostics.put(DIAGNOSTIC_MAX_DELIVERY_ATTEMPTS,
                effectiveMaxDeliveryAttempts);
        diagnostics.put(DIAGNOSTIC_DELIVERY_BUDGET_EXHAUSTED, true);
        diagnostics.put(DIAGNOSTIC_LEASE_ID, queuedTask.leaseId());
        if (queuedTask.leaseExpiresAt() != null) {
            diagnostics.put(DIAGNOSTIC_LEASE_EXPIRES_AT, queuedTask.leaseExpiresAt().toString());
        }
        return Map.copyOf(diagnostics);
    }

    private int effectiveMaxDeliveryAttempts() {
        return maxDeliveryAttempts > 0 ? maxDeliveryAttempts : DEFAULT_MAX_DELIVERY_ATTEMPTS;
    }

    private int effectiveMaxConcurrentTasks() {
        return effectiveConcurrency(maxConcurrentTasks);
    }

    private static int effectiveConcurrency(int value) {
        return value > 0 ? value : DEFAULT_MAX_CONCURRENT_TASKS;
    }

    private String nodeType(NodeExecutionTask task) {
        if (task.context() == null) {
            return "";
        }
        return String.valueOf(task.context().getOrDefault(NodeExecutionTask.NODE_TYPE_KEY, ""));
    }

    private Map<String, Object> executorSelectionContext(NodeExecutionTask task) {
        return task.executorSelectionPolicy().toSelectionContext();
    }

    private boolean isSpecificExecutorType(String executorType) {
        return executorType != null
                && !executorType.isBlank()
                && !"unspecified".equalsIgnoreCase(executorType);
    }

    private record LeaseRenewal(AtomicBoolean stopped, ScheduledFuture<?> future) {

        private static LeaseRenewal noop() {
            return new LeaseRenewal(new AtomicBoolean(true), null);
        }

        private void stop() {
            stopped.set(true);
            if (future != null) {
                future.cancel(false);
            }
        }
    }

    private record InFlightTask(
            String key,
            String messageId,
            String leaseId,
            String runId,
            String nodeId,
            Instant startedAt) {

        private static InFlightTask from(TaskQueue.QueuedTask queuedTask, long sequence) {
            NodeExecutionTask task = queuedTask != null ? queuedTask.task() : null;
            String messageId = queuedTask != null ? queuedTask.messageId() : "<null>";
            String leaseId = queuedTask != null ? queuedTask.leaseId() : "<null>";
            String runId = task != null && task.runId() != null ? task.runId().value() : "<null>";
            String nodeId = task != null && task.nodeId() != null ? task.nodeId().value() : "<null>";
            return new InFlightTask(
                    sequence + ":" + messageId + ":" + leaseId,
                    messageId,
                    leaseId,
                    runId,
                    nodeId,
                    Instant.now());
        }

        private String summary() {
            return "message=" + messageId
                    + " leaseId=" + leaseId
                    + " run=" + runId
                    + " node=" + nodeId
                    + " startedAt=" + startedAt;
        }

        private InFlightTaskStatus status(Instant observedAt) {
            long runningForMillis = Math.max(0L, Duration.between(startedAt, observedAt).toMillis());
            return new InFlightTaskStatus(
                    messageId,
                    leaseId,
                    runId,
                    nodeId,
                    startedAt,
                    runningForMillis);
        }
    }

    public enum WorkerState {
        RUNNING,
        DRAINING,
        PAUSED,
        STOPPED
    }

    public enum WorkerControlAction {
        PAUSE,
        DRAIN,
        RESUME
    }

    public record WorkerControlResult(
            WorkerControlAction action,
            boolean accepted,
            boolean completed,
            String reason,
            WorkerStatus status,
            Instant observedAt) {
    }

    public record WorkerStatus(
            WorkerState state,
            boolean running,
            boolean stopping,
            boolean operatorPaused,
            boolean subscriptionActive,
            String queueImplementation,
            String dispatcherImplementation,
            int maxConcurrentTasks,
            int inFlightCount,
            int remainingCapacity,
            boolean leaseRenewalEnabled,
            boolean leaseRenewalSchedulerRunning,
            String noExecutorDeferDelay,
            int maxNoExecutorDefers,
            int maxDeliveryAttempts,
            String dispatchTimeout,
            String shutdownGracePeriod,
            String leaseRenewalInterval,
            String leaseRenewalDuration,
            String consumeRetryInitialBackoff,
            String consumeRetryMaxBackoff,
            long acceptedTasks,
            long finishedTasks,
            long failedTasks,
            long skippedDueToStoppingTasks,
            long queueStreamFailures,
            String lastTaskFailure,
            Instant lastTaskFailureAt,
            String lastQueueStreamFailure,
            Instant lastQueueStreamFailureAt,
            Instant observedAt,
            List<InFlightTaskStatus> inFlightTasks) {

        public WorkerStatus {
            inFlightTasks = inFlightTasks == null ? List.of() : List.copyOf(inFlightTasks);
        }
    }

    public record InFlightTaskStatus(
            String messageId,
            String leaseId,
            String runId,
            String nodeId,
            Instant startedAt,
            long runningForMillis) {
    }

    private static final class TaskDispatchTimeoutException extends RuntimeException {

        private TaskDispatchTimeoutException(String message) {
            super(message);
        }
    }
}
