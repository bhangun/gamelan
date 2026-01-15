package tech.kayys.silat.scheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.sortedset.ScoreRange;
import io.quarkus.redis.datasource.sortedset.ZRangeArgs;
import io.quarkus.scheduler.Scheduled;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import tech.kayys.silat.dispatcher.TaskDispatcher;
import tech.kayys.silat.execution.NodeExecutionTask;
import tech.kayys.silat.api.event.EventPublisher;
import tech.kayys.silat.registry.ExecutorRegistry;
import tech.kayys.silat.model.ExecutorInfo;
import tech.kayys.silat.model.NodeId;
import tech.kayys.silat.model.RetryPolicy;
import tech.kayys.silat.model.WorkflowRunId;
import tech.kayys.silat.model.event.ExecutionEvent;

@ApplicationScoped
public class DefaultWorkflowScheduler implements WorkflowScheduler {

        private static final Logger LOG = LoggerFactory.getLogger(DefaultWorkflowScheduler.class);
        private static final String RETRY_ZSET = "workflow:tasks:retry:zset";

        @Inject
        ReactiveRedisDataSource redis;

        @Inject
        tech.kayys.silat.dispatcher.TaskDispatcherAggregator taskDispatcher;

        @Inject
        EventPublisher eventPublisher;

        @Inject
        ExecutorRegistry executorRegistry;

        // In-memory task tracking
        private final Map<String, ScheduledTask> scheduledTasks = new ConcurrentHashMap<>();

        @Override
        public Uni<Void> scheduleTask(NodeExecutionTask task) {
                String taskId = taskId(task);

                ScheduledTask scheduled = scheduledTasks.computeIfAbsent(
                                taskId,
                                id -> new ScheduledTask(
                                                id,
                                                task,
                                                Instant.now(),
                                                task.attempt(),
                                                TaskStatus.PENDING));

                LOG.info("Scheduling task [{}] run={}, node={}, attempt={}",
                                taskId,
                                task.runId().value(),
                                task.nodeId().value(),
                                task.attempt());

                return executorRegistry.getExecutorForNode(task.nodeId())
                                .flatMap((java.util.Optional<tech.kayys.silat.model.ExecutorInfo> executorOpt) -> {
                                        if (executorOpt.isEmpty()) {
                                                LOG.error("No executor found for node {}", task.nodeId().value());
                                                scheduled.markFailed(new RuntimeException("No executor available"));
                                                return Uni.createFrom().voidItem();
                                        }

                                        ExecutorInfo executor = executorOpt.get();

                                        // Log the dispatch attempt with more detail
                                        LOG.info("Attempting to dispatch task [{}] run={}, node={}, executor={}, commType={}",
                                                        taskId,
                                                        task.runId().value(),
                                                        task.nodeId().value(),
                                                        executor.executorId(),
                                                        executor.communicationType());

                                        return taskDispatcher.dispatch(task, executor)
                                                        .invoke(() -> {
                                                                LOG.debug("Task dispatch initiated successfully [{}]",
                                                                                taskId);
                                                                scheduled.markRunning();
                                                        })
                                                        .onFailure().invoke(err -> {
                                                                LOG.error("Task dispatch failed [{}]: {}", taskId,
                                                                                err.getMessage());
                                                        });
                                })
                                .onFailure().recoverWithUni(err -> {
                                        LOG.error("Task dispatch failed [{}]", taskId, err);
                                        scheduled.markFailed(err);

                                        RetryPolicy retryPolicy = task.retryPolicy();
                                        return handleDispatchFailure(task, err, retryPolicy);
                                })
                                .replaceWithVoid();

        }

        // ==================== RETRY ====================

        @Override
        public Uni<Void> scheduleRetry(
                        WorkflowRunId runId,
                        NodeId nodeId,
                        Duration delay) {

                long executeAt = Instant.now().plus(delay).toEpochMilli();
                String value = runId.value() + ":" + nodeId.value();

                LOG.info("Scheduling retry run={}, node={} at {}",
                                runId.value(), nodeId.value(), executeAt);

                return redis.sortedSet(String.class)
                                .zadd(RETRY_ZSET, executeAt, value)
                                .replaceWithVoid();
        }

        @Override
        public Uni<Void> cancelTasksForRun(WorkflowRunId runId) {
                scheduledTasks.values().stream()
                                .filter(t -> t.task().runId().equals(runId))
                                .forEach(ScheduledTask::markCancelled);

                return Uni.createFrom().voidItem();
        }

        // ==================== EVENTS ====================

        @Override
        public Uni<Void> publishEvents(List<ExecutionEvent> events) {
                return events.isEmpty()
                                ? Uni.createFrom().voidItem()
                                : eventPublisher.publish(events);
        }

        @Override
        public Uni<Long> getScheduledTasksCount() {
                return Uni.createFrom().item(
                                scheduledTasks.values().stream()
                                                .filter(t -> !t.status().isTerminal())
                                                .count());
        }

        /**
         * Background job to process retry queue
         */
        @Scheduled(every = "5s")
        void processRetryQueue() {

                long now = Instant.now().toEpochMilli();

                ScoreRange<Double> range = ScoreRange.from(0.0, (double) now);

                ZRangeArgs args = new ZRangeArgs().limit(0, 50);

                redis.sortedSet(String.class, String.class)
                                .zrangebyscore(RETRY_ZSET, range, args)
                                .subscribe().with(entries -> {
                                        for (String entry : entries) {
                                                handleRetryEntry(entry);
                                        }
                                });
        }

        private void handleRetryEntry(String entry) {

                String[] parts = entry.split(":");
                if (parts.length != 2) {
                        LOG.warn("Invalid retry entry {}", entry);
                        return;
                }

                WorkflowRunId runId = WorkflowRunId.of(parts[0]);
                NodeId nodeId = NodeId.of(parts[1]);

                redis.sortedSet(String.class, String.class)
                                .zrem(RETRY_ZSET, entry)
                                .subscribe().with(ignored -> {

                                        LOG.info("Retrying node {} for run {}", nodeId.value(), runId.value());

                                        // IMPORTANT: delegate to WorkflowRunManager
                                        eventPublisher.publishRetry(runId, nodeId)
                                                        .subscribe().with(
                                                                        v -> {
                                                                        },
                                                                        err -> LOG.error("Retry publish failed", err));
                                });
        }

        private String taskId(NodeExecutionTask task) {
                return task.runId().value() + ":" +
                                task.nodeId().value() + ":" +
                                task.attempt();
        }

        /**
         * Background job to clean up completed tasks
         */
        @Scheduled(every = "1m")
        void cleanupCompletedTasks() {
                Instant cutoff = Instant.now().minus(Duration.ofHours(1));

                List<String> toRemove = scheduledTasks.entrySet().stream()
                                .filter(entry -> {
                                        ScheduledTask task = entry.getValue();
                                        return task.status().isTerminal() &&
                                                        task.scheduledAt().isBefore(cutoff);
                                })
                                .map(Map.Entry::getKey)
                                .toList();

                toRemove.forEach(scheduledTasks::remove);

                if (!toRemove.isEmpty()) {
                        LOG.debug("Cleaned up {} completed tasks", toRemove.size());
                }
        }

        private Uni<Void> handleDispatchFailure(
                        NodeExecutionTask task,
                        Throwable failure,
                        RetryPolicy retryPolicy) {

                int attempt = task.attempt();

                if (retryPolicy == null || !retryPolicy.shouldRetry(attempt)) {
                        return publishEvents(List.of(
                                        ExecutionEvent.nodeDeadLettered(
                                                        task.runId(),
                                                        task.nodeId(),
                                                        failure.getMessage())));

                }

                Duration delay = retryPolicy.calculateDelay(attempt);

                return this.scheduleRetry(
                                task.runId(),
                                task.nodeId(),
                                delay);
        }

}
