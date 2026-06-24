package tech.kayys.gamelan.runtime.resource;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import tech.kayys.gamelan.scheduler.TaskDeadLetterQueue;
import tech.kayys.gamelan.scheduler.TaskQueue;
import tech.kayys.gamelan.scheduler.TaskWorker;

@ApplicationScoped
@Path("/api/v1/task-runtime")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class TaskRuntimeResource {

    @Inject
    TaskWorker taskWorker;

    @Inject
    TaskQueue taskQueue;

    @Inject
    TaskDeadLetterQueue deadLetterQueue;

    @ConfigProperty(name = "gamelan.task-runtime.readiness.accept-unknown", defaultValue = "true")
    Boolean readinessAcceptUnknown;

    @ConfigProperty(name = "gamelan.task-runtime.readiness.accept-stale-leases", defaultValue = "true")
    Boolean readinessAcceptStaleLeases;

    @ConfigProperty(name = "gamelan.task-runtime.readiness.accept-backlog", defaultValue = "true")
    Boolean readinessAcceptBacklog;

    @ConfigProperty(name = "gamelan.task-runtime.status.component-timeout", defaultValue = "2s")
    Duration statusComponentTimeout;

    @ConfigProperty(name = "gamelan.task-runtime.status.cache-ttl", defaultValue = "0s")
    Duration statusCacheTtl;

    private volatile CachedTaskRuntimeStatus cachedStatus;

    @GET
    @Path("/status")
    public Uni<TaskRuntimeStatus> status() {
        TaskRuntimeStatus cached = cachedStatusIfFresh(Instant.now());
        if (cached != null) {
            return Uni.createFrom().item(cached);
        }
        return computeStatus().map(this::cacheIfEnabled);
    }

    private Uni<TaskRuntimeStatus> computeStatus() {
        TaskWorker.WorkerStatus worker = taskWorker.status();
        Uni<ComponentStatus<TaskQueue.QueueStats>> queueStats =
                componentStatus("task queue stats", taskQueue::stats);
        Uni<ComponentStatus<Long>> deadLetters =
                componentStatus("task dead-letter count", deadLetterQueue::count);

        return Uni.combine().all().unis(queueStats, deadLetters).asTuple()
                .map(tuple -> {
                    ComponentStatus<TaskQueue.QueueStats> queue = tuple.getItem1();
                    ComponentStatus<Long> deadLetterStatus = tuple.getItem2();
                    List<TaskRuntimeIssue> issues = diagnose(worker, queue, deadLetterStatus);
                    return new TaskRuntimeStatus(
                            worker,
                            queue,
                            deadLetterStatus,
                            classify(worker, queue, deadLetterStatus, issues),
                            issues,
                            TaskRuntimeCache.disabled(),
                            Instant.now());
                });
    }

    @GET
    @Path("/readiness")
    public Uni<Response> readiness() {
        return status().map(status -> {
            TaskRuntimeReadiness readiness = TaskRuntimeReadiness.from(status, readinessPolicy());
            return Response.status(readiness.ready() ? Response.Status.OK : Response.Status.SERVICE_UNAVAILABLE)
                    .entity(readiness)
                    .build();
        });
    }

    @GET
    @Path("/liveness")
    public TaskRuntimeLiveness liveness() {
        return new TaskRuntimeLiveness(true, Instant.now());
    }

    private static List<TaskRuntimeIssue> diagnose(
            TaskWorker.WorkerStatus worker,
            ComponentStatus<TaskQueue.QueueStats> queue,
            ComponentStatus<Long> deadLetters) {
        List<TaskRuntimeIssue> issues = new ArrayList<>();
        if (worker.state() == TaskWorker.WorkerState.STOPPED) {
            issues.add(issue(
                    "worker-stopped",
                    "ERROR",
                    "Task worker is not consuming queued work."));
        } else if (worker.state() == TaskWorker.WorkerState.PAUSED) {
            issues.add(issue(
                    "worker-paused",
                    "ERROR",
                    "Task worker is paused and not consuming queued work."));
        } else if (worker.state() == TaskWorker.WorkerState.DRAINING) {
            issues.add(issue(
                    "worker-draining",
                    "ERROR",
                    "Task worker is draining in-flight work and not accepting new queued work."));
        }
        if (worker.queueStreamFailures() > 0) {
            issues.add(issue(
                    "worker-queue-stream-failures",
                    "WARN",
                    "Task worker observed queue consumer stream failures."));
        }
        if (!queue.available()) {
            issues.add(issue(
                    "queue-unavailable",
                    "ERROR",
                    "Task queue stats are unavailable: " + queue.error()));
        } else if (queue.value() == null || !queue.value().known()) {
            issues.add(issue(
                    "queue-stats-unknown",
                    "INFO",
                    "Task queue implementation does not expose exact backlog stats."));
        } else if (queue.value().health() == TaskQueue.QueueHealth.UNREADABLE_RECORDS) {
            issues.add(issue(
                    "queue-unreadable-records",
                    "ERROR",
                    "Task queue contains unreadable records that require operator triage."));
        } else if (queue.value().health() == TaskQueue.QueueHealth.STALE_LEASES) {
            issues.add(issue(
                    "queue-stale-leases",
                    "WARN",
                    "Task queue contains expired leases that should be reclaimed."));
        } else if (queue.value().health() == TaskQueue.QueueHealth.BACKLOG) {
            issues.add(issue(
                    "queue-backlog",
                    "INFO",
                    "Task queue has claimable work waiting for workers."));
        }
        if (!deadLetters.available()) {
            issues.add(issue(
                    "dead-letter-unavailable",
                    "WARN",
                    "Task dead-letter count is unavailable: " + deadLetters.error()));
        }
        if (worker.inFlightCount() > 0 && worker.remainingCapacity() == 0) {
            issues.add(issue(
                    "worker-at-capacity",
                    "WARN",
                    "Task worker is at configured in-flight capacity."));
        }
        return issues;
    }

    private static TaskRuntimeHealth classify(
            TaskWorker.WorkerStatus worker,
            ComponentStatus<TaskQueue.QueueStats> queue,
            ComponentStatus<Long> deadLetters,
            List<TaskRuntimeIssue> issues) {
        if (hasSeverity(issues, "ERROR") || !queue.available() || !deadLetters.available()) {
            return TaskRuntimeHealth.DEGRADED;
        }
        if (queue.value() == null || !queue.value().known()) {
            return TaskRuntimeHealth.UNKNOWN;
        }
        TaskQueue.QueueHealth queueHealth = queue.value().health();
        if (queueHealth == TaskQueue.QueueHealth.STALE_LEASES) {
            return TaskRuntimeHealth.STALE_LEASES;
        }
        if (queueHealth == TaskQueue.QueueHealth.BACKLOG
                || (worker.inFlightCount() > 0 && worker.remainingCapacity() == 0)) {
            return TaskRuntimeHealth.BACKLOG;
        }
        if (queueHealth == TaskQueue.QueueHealth.ACTIVE || worker.inFlightCount() > 0) {
            return TaskRuntimeHealth.ACTIVE;
        }
        if (queueHealth == TaskQueue.QueueHealth.IDLE) {
            return TaskRuntimeHealth.IDLE;
        }
        return TaskRuntimeHealth.UNKNOWN;
    }

    private static boolean hasSeverity(List<TaskRuntimeIssue> issues, String severity) {
        return issues.stream().anyMatch(issue -> severity.equals(issue.severity()));
    }

    private static TaskRuntimeIssue issue(String code, String severity, String message) {
        return new TaskRuntimeIssue(code, severity, message);
    }

    private TaskRuntimeReadinessPolicy readinessPolicy() {
        return new TaskRuntimeReadinessPolicy(
                defaultTrue(readinessAcceptUnknown),
                defaultTrue(readinessAcceptStaleLeases),
                defaultTrue(readinessAcceptBacklog));
    }

    private static boolean defaultTrue(Boolean value) {
        return value == null || value;
    }

    private <T> Uni<ComponentStatus<T>> componentStatus(String component, Supplier<Uni<T>> source) {
        long startedAtNanos = System.nanoTime();
        Uni<T> uni;
        try {
            uni = source.get();
        } catch (Throwable error) {
            return Uni.createFrom().item(ComponentStatus.unavailable(error, startedAtNanos));
        }
        if (uni == null) {
            return Uni.createFrom().item(ComponentStatus.unavailable(
                    new NullPointerException(component + " returned null Uni"), startedAtNanos));
        }
        Duration timeout = effectiveComponentTimeout();
        if (timeout != null) {
            uni = uni.ifNoItem().after(timeout).failWith(() -> new TaskRuntimeComponentTimeoutException(
                    component + " timed out after " + timeout));
        }
        return uni.map(value -> ComponentStatus.available(value, startedAtNanos))
                .onFailure().recoverWithItem(error -> ComponentStatus.unavailable(error, startedAtNanos));
    }

    private Duration effectiveComponentTimeout() {
        if (statusComponentTimeout == null
                || statusComponentTimeout.isZero()
                || statusComponentTimeout.isNegative()) {
            return null;
        }
        return statusComponentTimeout;
    }

    private TaskRuntimeStatus cachedStatusIfFresh(Instant now) {
        Duration ttl = effectiveStatusCacheTtl();
        if (ttl == null) {
            cachedStatus = null;
            return null;
        }
        CachedTaskRuntimeStatus cached = cachedStatus;
        if (cached == null || !ttl.equals(cached.ttl()) || cached.expired(now)) {
            if (cachedStatus == cached) {
                cachedStatus = null;
            }
            return null;
        }
        return cached.status().withCache(TaskRuntimeCache.hit(ttl, cached.cachedAt(), now));
    }

    private TaskRuntimeStatus cacheIfEnabled(TaskRuntimeStatus status) {
        Duration ttl = effectiveStatusCacheTtl();
        if (ttl == null) {
            cachedStatus = null;
            return status.withCache(TaskRuntimeCache.disabled());
        }
        Instant cachedAt = Instant.now();
        TaskRuntimeStatus cacheMiss = status.withCache(TaskRuntimeCache.miss(ttl, cachedAt));
        cachedStatus = new CachedTaskRuntimeStatus(cacheMiss, cachedAt, ttl);
        return cacheMiss;
    }

    private Duration effectiveStatusCacheTtl() {
        if (statusCacheTtl == null
                || statusCacheTtl.isZero()
                || statusCacheTtl.isNegative()) {
            return null;
        }
        return statusCacheTtl;
    }

    public record TaskRuntimeStatus(
            TaskWorker.WorkerStatus worker,
            ComponentStatus<TaskQueue.QueueStats> queue,
            ComponentStatus<Long> deadLetters,
            TaskRuntimeHealth health,
            List<TaskRuntimeIssue> issues,
            TaskRuntimeCache cache,
            Instant observedAt) {

        public TaskRuntimeStatus {
            issues = issues == null ? List.of() : List.copyOf(issues);
            cache = cache != null ? cache : TaskRuntimeCache.disabled();
        }

        private TaskRuntimeStatus withCache(TaskRuntimeCache cache) {
            return new TaskRuntimeStatus(
                    worker,
                    queue,
                    deadLetters,
                    health,
                    issues,
                    cache,
                    observedAt);
        }
    }

    public record TaskRuntimeCache(
            boolean enabled,
            boolean hit,
            long ttlMillis,
            long ageMillis,
            Instant expiresAt) {

        public TaskRuntimeCache {
            ttlMillis = Math.max(0L, ttlMillis);
            ageMillis = Math.max(0L, ageMillis);
        }

        private static TaskRuntimeCache disabled() {
            return new TaskRuntimeCache(false, false, 0, 0, null);
        }

        private static TaskRuntimeCache miss(Duration ttl, Instant cachedAt) {
            return new TaskRuntimeCache(
                    true,
                    false,
                    ttl.toMillis(),
                    0,
                    cachedAt.plus(ttl));
        }

        private static TaskRuntimeCache hit(Duration ttl, Instant cachedAt, Instant now) {
            return new TaskRuntimeCache(
                    true,
                    true,
                    ttl.toMillis(),
                    Duration.between(cachedAt, now).toMillis(),
                    cachedAt.plus(ttl));
        }
    }

    public enum TaskRuntimeHealth {
        DEGRADED,
        STALE_LEASES,
        BACKLOG,
        ACTIVE,
        IDLE,
        UNKNOWN
    }

    public record TaskRuntimeIssue(
            String code,
            String severity,
            String message) {
    }

    public record TaskRuntimeReadiness(
            boolean ready,
            TaskRuntimeHealth health,
            List<String> issueCodes,
            TaskRuntimeReadinessPolicy policy,
            String rejectionReason,
            Instant observedAt) {

        public TaskRuntimeReadiness {
            issueCodes = issueCodes == null ? List.of() : List.copyOf(issueCodes);
        }

        private static TaskRuntimeReadiness from(TaskRuntimeStatus status, TaskRuntimeReadinessPolicy policy) {
            List<String> issueCodes = status.issues().stream()
                    .map(TaskRuntimeIssue::code)
                    .toList();
            boolean ready = policy.ready(status.health());
            return new TaskRuntimeReadiness(
                    ready,
                    status.health(),
                    issueCodes,
                    policy,
                    ready ? null : rejectionReason(status.health()),
                    status.observedAt());
        }

        private static String rejectionReason(TaskRuntimeHealth health) {
            return health == TaskRuntimeHealth.DEGRADED
                    ? "runtime-degraded"
                    : "health-not-accepted-by-readiness-policy";
        }
    }

    public record TaskRuntimeReadinessPolicy(
            boolean acceptUnknown,
            boolean acceptStaleLeases,
            boolean acceptBacklog) {

        private boolean ready(TaskRuntimeHealth health) {
            return switch (health) {
                case DEGRADED -> false;
                case UNKNOWN -> acceptUnknown;
                case STALE_LEASES -> acceptStaleLeases;
                case BACKLOG -> acceptBacklog;
                case ACTIVE, IDLE -> true;
            };
        }
    }

    public record TaskRuntimeLiveness(
            boolean alive,
            Instant observedAt) {
    }

    private static final class TaskRuntimeComponentTimeoutException extends RuntimeException {

        private TaskRuntimeComponentTimeoutException(String message) {
            super(message);
        }
    }

    private record CachedTaskRuntimeStatus(
            TaskRuntimeStatus status,
            Instant cachedAt,
            Duration ttl) {

        private boolean expired(Instant now) {
            return !now.isBefore(cachedAt.plus(ttl));
        }
    }

    public record ComponentStatus<T>(
            boolean available,
            T value,
            String error,
            long durationMillis,
            boolean timedOut,
            Instant observedAt) {

        public ComponentStatus {
            durationMillis = Math.max(0L, durationMillis);
            observedAt = observedAt != null ? observedAt : Instant.now();
        }

        private static <T> ComponentStatus<T> available(T value, long startedAtNanos) {
            return new ComponentStatus<>(
                    true,
                    value,
                    null,
                    elapsedMillis(startedAtNanos),
                    false,
                    Instant.now());
        }

        private static <T> ComponentStatus<T> unavailable(Throwable error, long startedAtNanos) {
            return new ComponentStatus<>(
                    false,
                    null,
                    errorSummary(error),
                    elapsedMillis(startedAtNanos),
                    causedByTimeout(error),
                    Instant.now());
        }

        private static long elapsedMillis(long startedAtNanos) {
            return Math.max(0L, (System.nanoTime() - startedAtNanos) / 1_000_000L);
        }

        private static boolean causedByTimeout(Throwable error) {
            Throwable cursor = error;
            while (cursor != null) {
                if (cursor instanceof TaskRuntimeComponentTimeoutException) {
                    return true;
                }
                cursor = cursor.getCause();
            }
            return false;
        }

        private static String errorSummary(Throwable error) {
            if (error == null) {
                return "unknown";
            }
            String message = error.getMessage();
            return message == null || message.isBlank()
                    ? error.getClass().getSimpleName()
                    : error.getClass().getSimpleName() + ": " + message.replaceAll("\\s+", " ").trim();
        }
    }
}
