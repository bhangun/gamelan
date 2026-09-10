package tech.kayys.gamelan.workflow;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.UUID;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.quarkus.runtime.Startup;
import io.quarkus.scheduler.Scheduled;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import tech.kayys.gamelan.engine.SystemClock;
import tech.kayys.gamelan.engine.error.ErrorInfo;
import tech.kayys.gamelan.engine.event.EventStore;
import tech.kayys.gamelan.engine.event.WorkflowRunUpdateEvent;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupPublisher;
import tech.kayys.gamelan.engine.node.NodeExecution;
import tech.kayys.gamelan.engine.node.NodeExecutionStatus;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.repository.WorkflowDefinitionRepository;
import tech.kayys.gamelan.engine.repository.WorkflowRecoveryLease;
import tech.kayys.gamelan.engine.repository.WorkflowRecoveryLeaseRepository;
import tech.kayys.gamelan.engine.repository.WorkflowRunRepository;
import tech.kayys.gamelan.engine.repository.WorkflowRunRecoveryCursor;
import tech.kayys.gamelan.engine.repository.WorkflowRunRecoveryPage;
import tech.kayys.gamelan.engine.run.RunStatus;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinition;
import tech.kayys.gamelan.engine.workflow.WorkflowReplayConsistencyChecker;
import tech.kayys.gamelan.engine.workflow.WorkflowRun;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunSnapshot;
import tech.kayys.gamelan.scheduler.RetryManager;

@Startup
@ApplicationScoped
public class WorkflowRecoveryService {

    static final String RUN_UPDATED_ADDRESS = WorkflowRunUpdateEvent.ADDRESS;
    private static final Logger LOG = LoggerFactory.getLogger(WorkflowRecoveryService.class);

    @Inject
    WorkflowRunRepository runRepository;

    @Inject
    Instance<EventStore> eventStores;

    @Inject
    Instance<WorkflowDefinitionRepository> definitionRepositories;

    @Inject
    Instance<WorkflowRecoveryLeaseRepository> recoveryLeaseRepositories;

    EventStore eventStore;

    WorkflowDefinitionRepository definitionRepository;

    WorkflowRecoveryLeaseRepository recoveryLeaseRepository;

    @Inject
    EventBus eventBus;

    @Inject
    WorkflowRunWakeupPublisher wakeupPublisher;

    @Inject
    MeterRegistry meterRegistry;

    @Inject
    SystemClock clock;

    @Inject
    RetryManager retryManager;

    @Inject
    WorkflowRecoveryAnalyzer analyzer;

    @ConfigProperty(name = "gamelan.recovery.enabled", defaultValue = "true")
    boolean enabled;

    @ConfigProperty(name = "gamelan.recovery.page-size", defaultValue = "100")
    int pageSize;

    @ConfigProperty(name = "gamelan.recovery.max-concurrent-runs", defaultValue = "4")
    int maxConcurrentRuns;

    @ConfigProperty(name = "gamelan.recovery.max-scan-runs", defaultValue = "10000")
    int maxScanRuns;

    @ConfigProperty(name = "gamelan.recovery.timeout-grace", defaultValue = "30s")
    Duration timeoutGrace;

    @ConfigProperty(name = "gamelan.recovery.replay-consistency-mode", defaultValue = "best-effort")
    String replayConsistencyMode;

    @ConfigProperty(name = "gamelan.recovery.distributed-lease.enabled", defaultValue = "false")
    boolean distributedLeaseEnabled;

    @ConfigProperty(name = "gamelan.recovery.distributed-lease.name", defaultValue = "workflow-recovery")
    String distributedLeaseName;

    @ConfigProperty(name = "gamelan.recovery.distributed-lease.ttl", defaultValue = "2m")
    Duration distributedLeaseTtl;

    @ConfigProperty(name = "gamelan.recovery.distributed-lease.renew-interval", defaultValue = "0s")
    Duration distributedLeaseRenewInterval;

    @ConfigProperty(name = "gamelan.recovery.distributed-lease.owner-id", defaultValue = "")
    String distributedLeaseOwnerId;

    @ConfigProperty(name = "gamelan.engine.id", defaultValue = "")
    String engineId;

    private RecoveryMetrics recoveryMetrics;
    private final AtomicBoolean sweepInProgress = new AtomicBoolean();
    private final String fallbackRecoveryLeaseOwnerId = "gamelan-recovery-" + UUID.randomUUID();

    @Scheduled(every = "{gamelan.recovery.scan-interval}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void sweepScheduled() {
        if (!enabled) {
            return;
        }
        recoverActiveRuns()
                .subscribe().with(
                        result -> LOG.debug(
                                "Workflow recovery sweep completed scanned={}, skippedSweeps={}, noWorkRuns={}, deferredActiveRuns={}, skippedAfterLockRuns={}, scanPages={}, scanCandidates={}, duplicateScanCandidates={}, cursorStalls={}, dueRetries={}, staleExecutions={}, replayDrifts={}, replayUnavailable={}, replayMismatches={}, failedRuns={}, scanLimitReached={}",
                                result.scannedRuns(),
                                result.skippedSweeps(),
                                result.noWorkRuns(),
                                result.deferredActiveRuns(),
                                result.skippedAfterLockRuns(),
                                result.scanPages(),
                                result.scanCandidates(),
                                result.duplicateScanCandidates(),
                                result.scanCursorStalls(),
                                result.dueRetries(),
                                result.staleExecutions(),
                                result.replayDrifts(),
                                result.replayUnavailable(),
                                result.replayMismatchCount(),
                                result.failedRuns(),
                                result.scanLimitReached()),
                        error -> {
                            LOG.warn("Workflow recovery sweep failed: {}", error.getMessage());
                            LOG.debug("Workflow recovery sweep failure details", error);
                        });
    }

    public Uni<RecoverySweepResult> recoverActiveRuns() {
        if (!enabled) {
            return Uni.createFrom().item(RecoverySweepResult.empty());
        }
        RecoveryMetrics metrics = recoveryMetrics();
        Timer.Sample sweepSample = metrics.startSweep();
        if (!sweepInProgress.compareAndSet(false, true)) {
            RecoverySweepResult skipped = RecoverySweepResult.skipped(RecoverySweepSkipReason.ALREADY_RUNNING);
            metrics.recordSweepSkipped(skipped, sweepSample);
            return Uni.createFrom().item(skipped);
        }
        return acquireRecoveryLease(metrics)
                .flatMap(leaseAttempt -> executeRecoverySweepWithLease(metrics, leaseAttempt, sweepSample))
                .invoke(result -> {
                    if (result != null && result.skippedSweeps() == 0) {
                        metrics.recordSweepSuccess(result, sweepSample);
                    }
                })
                .onFailure().invoke(error -> metrics.recordSweepFailure(sweepSample))
                .onTermination().invoke(() -> sweepInProgress.set(false));
    }

    private Uni<RecoverySweepResult> executeRecoverySweepWithLease(
            RecoveryMetrics metrics,
            RecoveryLeaseAttempt leaseAttempt,
            Timer.Sample sweepSample) {
        RecoveryLeaseAttempt safeAttempt = leaseAttempt != null ? leaseAttempt : RecoveryLeaseAttempt.notRequired();
        if (safeAttempt.skipReason() != null) {
            RecoverySweepResult skipped = RecoverySweepResult.skipped(safeAttempt.skipReason());
            metrics.recordSweepSkipped(skipped, sweepSample);
            return Uni.createFrom().item(skipped);
        }
        return executeRecoverySweep(metrics, safeAttempt.context())
                .onItemOrFailure().transformToUni((result, failure) -> releaseRecoveryLease(safeAttempt.lease(), metrics)
                        .flatMap(ignored -> {
                            if (failure != null) {
                                return Uni.createFrom().failure(failure);
                            }
                            return Uni.createFrom().item(result);
                        }));
    }

    private Uni<RecoverySweepResult> executeRecoverySweep(
            RecoveryMetrics metrics,
            RecoveryLeaseContext leaseContext) {
        int safePageSize = pageSize > 0 ? pageSize : 100;
        int safeMaxScanRuns = maxScanRuns > 0 ? maxScanRuns : Integer.MAX_VALUE;
        return collectActiveRuns(
                        metrics,
                        leaseContext,
                        WorkflowRunRecoveryCursor.start(),
                        safePageSize,
                        safeMaxScanRuns,
                        0,
                        0,
                        0,
                        0,
                        0,
                        new LinkedHashMap<>())
                .flatMap(scan -> renewRecoveryLeaseIfDue(leaseContext, metrics)
                        .replaceWith(scan))
                .flatMap(scan -> recoverRuns(scan.runs(), leaseContext, metrics)
                        .map(result -> result.withScanTelemetry(scan)));
    }

    private Uni<RecoveryRunScan> collectActiveRuns(
            RecoveryMetrics metrics,
            RecoveryLeaseContext leaseContext,
            WorkflowRunRecoveryCursor cursor,
            int safePageSize,
            int safeMaxScanRuns,
            long scannedCandidates,
            int scanPages,
            int duplicateCandidates,
            int invalidCandidates,
            int cursorStalls,
            LinkedHashMap<RecoveryRunKey, WorkflowRun> accumulated) {
        WorkflowRunRecoveryCursor safeCursor = cursor != null ? cursor : WorkflowRunRecoveryCursor.start();
        return renewRecoveryLeaseIfDue(leaseContext, metrics)
                .flatMap(ignored -> runRepository.scanActiveRunsForRecovery(safeCursor, safePageSize))
                .flatMap(page -> {
                    WorkflowRunRecoveryPage safePage = page != null
                            ? page
                            : new WorkflowRunRecoveryPage(List.of(), safeCursor, false);
                    List<WorkflowRun> runs = safePage.runs();
                    int pageCount = scanPages + 1;
                    long candidateCount = scannedCandidates;
                    int duplicateCount = duplicateCandidates;
                    int invalidCount = invalidCandidates;
                    if (!runs.isEmpty()) {
                        for (WorkflowRun run : runs) {
                            if (candidateCount >= safeMaxScanRuns) {
                                return Uni.createFrom().item(new RecoveryRunScan(
                                        List.copyOf(accumulated.values()),
                                        pageCount,
                                        (int) candidateCount,
                                        duplicateCount,
                                        invalidCount,
                                        cursorStalls,
                                        true));
                            }
                            candidateCount++;
                            RecoveryRunKey key = recoveryRunKey(run);
                            if (key != null) {
                                WorkflowRun previous = accumulated.putIfAbsent(key, run);
                                if (previous != null) {
                                    duplicateCount++;
                                }
                            } else {
                                invalidCount++;
                            }
                        }
                    }
                    if (!safePage.hasMore()) {
                        return Uni.createFrom().item(new RecoveryRunScan(
                                List.copyOf(accumulated.values()),
                                pageCount,
                                (int) candidateCount,
                                duplicateCount,
                                invalidCount,
                                cursorStalls,
                                false));
                    }
                    if (candidateCount >= safeMaxScanRuns) {
                        return Uni.createFrom().item(new RecoveryRunScan(
                                List.copyOf(accumulated.values()),
                                pageCount,
                                (int) candidateCount,
                                duplicateCount,
                                invalidCount,
                                cursorStalls,
                                true));
                    }
                    WorkflowRunRecoveryCursor nextCursor = safePage.nextCursor();
                    if (safeCursor.equals(nextCursor)) {
                        return Uni.createFrom().item(new RecoveryRunScan(
                                List.copyOf(accumulated.values()),
                                pageCount,
                                (int) candidateCount,
                                duplicateCount,
                                invalidCount,
                                cursorStalls + 1,
                                false));
                    }
                    return collectActiveRuns(
                            metrics,
                            leaseContext,
                            nextCursor,
                            safePageSize,
                            safeMaxScanRuns,
                            candidateCount,
                            pageCount,
                            duplicateCount,
                            invalidCount,
                            cursorStalls,
                            accumulated);
                });
    }

    private Uni<RecoverySweepResult> recoverRuns(
            List<WorkflowRun> runs,
            RecoveryLeaseContext leaseContext,
            RecoveryMetrics metrics) {
        if (runs == null || runs.isEmpty()) {
            return Uni.createFrom().item(RecoverySweepResult.empty());
        }

        int concurrency = effectiveMaxConcurrentRuns();
        Uni<RecoverySweepResult> recovery = Uni.createFrom().item(RecoverySweepResult.empty());
        for (int start = 0; start < runs.size(); start += concurrency) {
            int end = Math.min(start + concurrency, runs.size());
            List<WorkflowRun> chunk = new ArrayList<>(runs.subList(start, end));
            recovery = recovery.chain(accumulated -> renewRecoveryLeaseIfDue(leaseContext, metrics)
                    .flatMap(ignored -> recoverRunChunk(chunk))
                    .map(accumulated::plus));
        }
        return recovery;
    }

    private Uni<RecoverySweepResult> recoverRunChunk(List<WorkflowRun> runs) {
        if (runs == null || runs.isEmpty()) {
            return Uni.createFrom().item(RecoverySweepResult.empty());
        }
        return Uni.combine().all().unis(
                runs.stream()
                        .map(this::recoverRunSafely)
                        .toList())
                .with(results -> {
                    List<RecoveryRunResult> runResults = new ArrayList<>(results.size());
                    for (Object result : results) {
                        runResults.add((RecoveryRunResult) result);
                    }
                    return RecoverySweepResult.fromRunResults(runResults);
                });
    }

    private Uni<RecoveryRunResult> recoverRunSafely(WorkflowRun run) {
        return recoverRun(run)
                .onFailure().recoverWithItem(error -> {
                    LOG.warn("Workflow recovery skipped run={} after failure: {}",
                            safeRunId(run),
                            error.getMessage());
                    LOG.debug("Workflow recovery run failure details", error);
                    return RecoveryRunResult.failed(run);
                });
    }

    public Uni<RecoveryRunResult> recoverRun(WorkflowRun run) {
        if (run == null || run.getStatus().isTerminal()) {
            return Uni.createFrom().item(RecoveryRunResult.empty());
        }
        WorkflowRunId runId = run.getId();
        TenantId tenantId = run.getTenantId();
        return runRepository.withLock(runId, tenantId, lockedRun -> recoverLockedRun(lockedRun))
                .call(this::publishWakeup);
    }

    private Uni<RecoveryRunResult> recoverLockedRun(WorkflowRun run) {
        if (run == null || run.getStatus() == null || run.getStatus().isTerminal()) {
            return Uni.createFrom().item(RecoveryRunResult.skippedAfterLock(run));
        }
        if (run.getStatus() != RunStatus.RUNNING) {
            return Uni.createFrom().item(RecoveryRunResult.deferredActive(run));
        }

        Instant now = now();
        return replayConsistency(run)
                .flatMap(consistency -> recoverConsistentLockedRun(run, now, consistency));
    }

    private Uni<RecoveryRunResult> recoverConsistentLockedRun(
            WorkflowRun run,
            Instant now,
            WorkflowReplayConsistencyChecker.Report replayConsistency) {

        WorkflowRecoveryPlan plan = analyzer().analyze(run, now, timeoutGrace, replayConsistency);
        if (plan.hasReplayConsistencyBlock()) {
            WorkflowReplayConsistencyChecker.Report report = plan.replayConsistency();
            LOG.warn("Workflow recovery blocked mutation after replay consistency {} run={}, tenant={}, mismatches={}",
                    report.status().name().toLowerCase(),
                    run.getId().value(),
                    run.getTenantId().value(),
                    report.mismatches().size());
            return Uni.createFrom().item(RecoveryRunResult.replayBlocked(run, report));
        }
        if (!plan.hasWork()) {
            return Uni.createFrom().item(RecoveryRunResult.noWork(run));
        }

        List<RetrySchedule> retrySchedules = retrySchedulesFor(plan.retryWakeups(), now);
        int staleRecovered = reapStaleExecutions(run, plan.staleExecutions(), now, retrySchedules);
        boolean mutated = staleRecovered > 0;
        RecoveryRunResult result = new RecoveryRunResult(
                run.getId(),
                run.getTenantId(),
                1,
                plan.dueRetryNodes().size(),
                staleRecovered,
                retrySchedules.size(),
                mutated,
                false,
                false,
                false,
                List.of(),
                plan.dueRetryNodes().size() > 0 || mutated);

        if (!mutated) {
            return scheduleRetryWakeups(run.getId(), run.getTenantId(), retrySchedules)
                    .replaceWith(result);
        }
        return runRepository.update(run)
                .call(() -> scheduleRetryWakeups(run.getId(), run.getTenantId(), retrySchedules))
                .replaceWith(result);
    }

    private Uni<WorkflowReplayConsistencyChecker.Report> replayConsistency(WorkflowRun run) {
        ReplayConsistencyMode mode = replayConsistencyMode();
        if (mode == ReplayConsistencyMode.DISABLED) {
            return Uni.createFrom().nullItem();
        }

        EventStore store = recoveryEventStore();
        WorkflowDefinitionRepository definitions = recoveryDefinitionRepository();
        if (run == null) {
            return Uni.createFrom().nullItem();
        }
        if (store == null || definitions == null) {
            if (mode == ReplayConsistencyMode.STRICT) {
                return Uni.createFrom().item(replayLoadFailure(
                        run,
                        "replayConsistency",
                        replayConsistencyUnavailableMessage(store, definitions)));
            }
            return Uni.createFrom().nullItem();
        }

        return definitions.findByIdIncludingInactive(run.getDefinitionId(), run.getTenantId())
                .flatMap(definition -> {
                    if (definition == null) {
                        return Uni.createFrom().item(replayLoadFailure(
                                run,
                                "definition",
                                "WorkflowDefinition not found: " + run.getDefinitionId().value()));
                    }
                    WorkflowRunSnapshot snapshot = run.createSnapshot();
                    return store.getEvents(run.getId(), run.getTenantId())
                            .map(events -> WorkflowReplayConsistencyChecker.compare(snapshot, definition, events));
                })
                .onFailure().recoverWithItem(error -> {
                    LOG.warn("Workflow recovery could not validate replay consistency run={}: {}",
                            run.getId().value(),
                            error.getMessage());
                    LOG.debug("Workflow replay consistency validation failure details", error);
                    return replayLoadFailure(run, "replayConsistency", error.getMessage());
                });
    }

    private String replayConsistencyUnavailableMessage(
            EventStore store,
            WorkflowDefinitionRepository definitions) {
        List<String> missing = new ArrayList<>();
        if (store == null) {
            missing.add("EventStore");
        }
        if (definitions == null) {
            missing.add("WorkflowDefinitionRepository");
        }
        return "Strict replay consistency requires " + String.join(" and ", missing);
    }

    private WorkflowReplayConsistencyChecker.Report replayLoadFailure(
            WorkflowRun run,
            String field,
            String message) {
        return new WorkflowReplayConsistencyChecker.Report(
                run.getId(),
                WorkflowReplayConsistencyChecker.Status.UNAVAILABLE,
                List.of(new WorkflowReplayConsistencyChecker.Mismatch(
                        field,
                        "available",
                        message != null && !message.isBlank() ? message : "unavailable")));
    }

    private List<RetrySchedule> retrySchedulesFor(
            List<WorkflowRecoveryPlan.RetryWakeup> retryWakeups,
            Instant now) {
        if (retryWakeups == null || retryWakeups.isEmpty()) {
            return new ArrayList<>();
        }
        List<RetrySchedule> schedules = new ArrayList<>(retryWakeups.size());
        for (WorkflowRecoveryPlan.RetryWakeup retryWakeup : retryWakeups) {
            if (retryWakeup == null || retryWakeup.nodeId() == null || retryWakeup.attempt() <= 0
                    || retryWakeup.retryAt() == null) {
                continue;
            }
            schedules.add(new RetrySchedule(
                    retryWakeup.nodeId(),
                    retryWakeup.attempt(),
                    delayUntil(retryWakeup.retryAt(), now)));
        }
        return schedules;
    }

    private int reapStaleExecutions(
            WorkflowRun run,
            List<WorkflowRecoveryPlan.StaleNodeExecution> staleExecutions,
            Instant now,
            List<RetrySchedule> retrySchedules) {
        int recovered = 0;
        for (WorkflowRecoveryPlan.StaleNodeExecution stale : staleExecutions) {
            NodeExecution current = run.getAllNodeExecutions().get(stale.nodeId());
            if (current == null
                    || current.getAttempt() != stale.attempt()
                    || !isInFlight(current.getStatus())) {
                continue;
            }

            run.failNode(stale.nodeId(), stale.attempt(), timeoutError(stale, now));
            recovered++;

            NodeExecution after = run.getAllNodeExecutions().get(stale.nodeId());
            if (after != null && after.canRetry() && after.getRetryAt() != null) {
                retrySchedules.add(new RetrySchedule(
                        stale.nodeId(),
                        after.getAttempt(),
                        delayUntil(after.getRetryAt(), now)));
            }
        }
        return recovered;
    }

    private Uni<Void> scheduleRetryWakeups(WorkflowRunId runId, TenantId tenantId, List<RetrySchedule> retrySchedules) {
        if (retrySchedules == null || retrySchedules.isEmpty() || retryManager == null) {
            return Uni.createFrom().voidItem();
        }
        return Multi.createFrom().iterable(retrySchedules)
                .onItem().transformToUniAndConcatenate(schedule -> retryManager
                        .scheduleRetry(runId, tenantId, schedule.nodeId(), schedule.attempt(), schedule.delay())
                        .onFailure().invoke(error -> LOG.warn(
                                "Recovery retry wake-up scheduling failed run={}, node={}, attempt={}: {}",
                                runId.value(),
                                schedule.nodeId().value(),
                                schedule.attempt(),
                                error.getMessage()))
                        .onFailure().recoverWithNull())
                .collect().asList()
                .replaceWithVoid();
    }

    private Uni<Void> publishWakeup(RecoveryRunResult result) {
        if (result == null || !result.shouldWakeRun()) {
            return Uni.createFrom().voidItem();
        }
        WorkflowRunUpdateEvent event = WorkflowRunUpdateEvent.of(
                result.runId(),
                result.tenantId(),
                result.mutated() ? "recovery-mutated" : "recovery-wakeup");
        Uni<Void> publish;
        if (wakeupPublisher != null) {
            publish = wakeupPublisher.publish(event);
        } else if (eventBus != null) {
            eventBus.publish(RUN_UPDATED_ADDRESS, JsonObject.mapFrom(event));
            publish = Uni.createFrom().voidItem();
        } else {
            LOG.warn("No workflow run wake-up publisher available for recovery run={}, reason={}",
                    event.runId(), event.reason());
            publish = Uni.createFrom().voidItem();
        }
        return publish.onFailure().invoke(error -> LOG.warn(
                "Workflow recovery wake-up publish failed run={}, reason={}: {}",
                event.runId(),
                event.reason(),
                error.getMessage()))
                .onFailure().recoverWithNull();
    }

    private ErrorInfo timeoutError(WorkflowRecoveryPlan.StaleNodeExecution stale, Instant detectedAt) {
        return new ErrorInfo(
                "NODE_EXECUTION_TIMEOUT",
                "Node " + stale.nodeId().value() + " exceeded execution timeout",
                "",
                Map.of(
                        "nodeId", stale.nodeId().value(),
                        "attempt", stale.attempt(),
                        "startedAt", stale.startedAt().toString(),
                        "deadline", stale.deadline().toString(),
                        "timeout", stale.timeout().toString(),
                        "grace", stale.grace().toString(),
                        "detectedAt", detectedAt.toString()));
    }

    private boolean isInFlight(NodeExecutionStatus status) {
        return status == NodeExecutionStatus.RUNNING || status == NodeExecutionStatus.EXECUTING;
    }

    private Duration delayUntil(Instant instant, Instant now) {
        Duration delay = Duration.between(now, instant);
        return delay.isNegative() ? Duration.ZERO : delay;
    }

    private Instant now() {
        return clock != null ? clock.now() : Instant.now();
    }

    private WorkflowRecoveryAnalyzer analyzer() {
        return analyzer != null ? analyzer : new WorkflowRecoveryAnalyzer();
    }

    private EventStore recoveryEventStore() {
        if (eventStore != null) {
            return eventStore;
        }
        if (eventStores == null || eventStores.isUnsatisfied() || eventStores.isAmbiguous()) {
            return null;
        }
        return eventStores.get();
    }

    private WorkflowDefinitionRepository recoveryDefinitionRepository() {
        if (definitionRepository != null) {
            return definitionRepository;
        }
        if (definitionRepositories == null || definitionRepositories.isUnsatisfied()
                || definitionRepositories.isAmbiguous()) {
            return null;
        }
        return definitionRepositories.get();
    }

    private WorkflowRecoveryLeaseRepository recoveryLeaseRepository() {
        if (recoveryLeaseRepository != null) {
            return recoveryLeaseRepository;
        }
        if (recoveryLeaseRepositories == null || recoveryLeaseRepositories.isUnsatisfied()
                || recoveryLeaseRepositories.isAmbiguous()) {
            return null;
        }
        return recoveryLeaseRepositories.get();
    }

    private Uni<RecoveryLeaseAttempt> acquireRecoveryLease(RecoveryMetrics metrics) {
        if (!distributedLeaseEnabled) {
            return Uni.createFrom().item(RecoveryLeaseAttempt.notRequired());
        }
        WorkflowRecoveryLeaseRepository repository = recoveryLeaseRepository();
        if (repository == null) {
            LOG.warn("Workflow recovery distributed lease is enabled but no WorkflowRecoveryLeaseRepository is available; skipping sweep");
            RecoveryLeaseAttempt attempt = RecoveryLeaseAttempt.skipped(
                    RecoverySweepSkipReason.LEASE_REPOSITORY_UNAVAILABLE);
            metrics.recordLeaseOperation(RecoveryLeaseOperation.ACQUIRE, leaseOutcome(attempt));
            return Uni.createFrom().item(attempt);
        }
        return repository.tryAcquireRecoveryLease(
                effectiveDistributedLeaseName(),
                effectiveRecoveryLeaseOwnerId(),
                effectiveDistributedLeaseTtl(),
                now())
                .onFailure().invoke(error -> metrics.recordLeaseOperation(
                        RecoveryLeaseOperation.ACQUIRE,
                        RecoveryLeaseOutcome.FAILURE))
                .map(RecoveryLeaseAttempt::from)
                .invoke(attempt -> metrics.recordLeaseOperation(
                        RecoveryLeaseOperation.ACQUIRE,
                        leaseOutcome(attempt)));
    }

    private Uni<Void> renewRecoveryLeaseIfDue(RecoveryLeaseContext context, RecoveryMetrics metrics) {
        if (context == null || context.lease() == null) {
            return Uni.createFrom().voidItem();
        }
        Instant current = now();
        if (!context.shouldRenew(current, effectiveDistributedLeaseRenewInterval())) {
            return Uni.createFrom().voidItem();
        }
        WorkflowRecoveryLeaseRepository repository = recoveryLeaseRepository();
        if (repository == null) {
            metrics.recordLeaseOperation(
                    RecoveryLeaseOperation.RENEW,
                    RecoveryLeaseOutcome.REPOSITORY_UNAVAILABLE);
            return Uni.createFrom().failure(new IllegalStateException(
                    "Workflow recovery lease renewal failed because no WorkflowRecoveryLeaseRepository is available"));
        }
        WorkflowRecoveryLease lease = context.lease();
        return repository.tryAcquireRecoveryLease(
                        lease.leaseName(),
                        lease.ownerId(),
                        effectiveDistributedLeaseTtl(),
                        current)
                .onFailure().invoke(error -> metrics.recordLeaseOperation(
                        RecoveryLeaseOperation.RENEW,
                        RecoveryLeaseOutcome.FAILURE))
                .map(RecoveryLeaseAttempt::from)
                .flatMap(attempt -> {
                    metrics.recordLeaseOperation(RecoveryLeaseOperation.RENEW, leaseOutcome(attempt));
                    if (attempt.skipReason() != null) {
                        return Uni.createFrom().failure(new IllegalStateException(
                                "Workflow recovery lease renewal failed: " + attempt.skipReason().metricName()));
                    }
                    context.update(attempt.lease());
                    return Uni.createFrom().voidItem();
                });
    }

    private Uni<Void> releaseRecoveryLease(WorkflowRecoveryLease lease, RecoveryMetrics metrics) {
        if (lease == null || !lease.acquired()) {
            return Uni.createFrom().voidItem();
        }
        WorkflowRecoveryLeaseRepository repository = recoveryLeaseRepository();
        if (repository == null) {
            metrics.recordLeaseOperation(
                    RecoveryLeaseOperation.RELEASE,
                    RecoveryLeaseOutcome.REPOSITORY_UNAVAILABLE);
            return Uni.createFrom().voidItem();
        }
        return repository.releaseRecoveryLease(lease)
                .invoke(() -> metrics.recordLeaseOperation(
                        RecoveryLeaseOperation.RELEASE,
                        RecoveryLeaseOutcome.SUCCESS))
                .onFailure().invoke(error -> LOG.warn(
                        "Workflow recovery lease release failed lease={}, owner={}: {}",
                        lease.leaseName(),
                        lease.ownerId(),
                        error.getMessage()))
                .onFailure().invoke(error -> metrics.recordLeaseOperation(
                        RecoveryLeaseOperation.RELEASE,
                        RecoveryLeaseOutcome.FAILURE))
                .onFailure().recoverWithNull();
    }

    private RecoveryLeaseOutcome leaseOutcome(RecoveryLeaseAttempt attempt) {
        if (attempt == null || attempt.skipReason() == RecoverySweepSkipReason.LEASE_REPOSITORY_UNAVAILABLE) {
            return RecoveryLeaseOutcome.REPOSITORY_UNAVAILABLE;
        }
        if (attempt.skipReason() == RecoverySweepSkipReason.LEASE_HELD) {
            return RecoveryLeaseOutcome.HELD;
        }
        return RecoveryLeaseOutcome.SUCCESS;
    }

    private String effectiveDistributedLeaseName() {
        return distributedLeaseName != null && !distributedLeaseName.isBlank()
                ? distributedLeaseName.trim()
                : "workflow-recovery";
    }

    private String effectiveRecoveryLeaseOwnerId() {
        String configuredOwnerId = normalizedText(distributedLeaseOwnerId);
        if (configuredOwnerId != null) {
            return configuredOwnerId;
        }
        String configuredEngineId = normalizedText(engineId);
        if (configuredEngineId != null) {
            return "gamelan-recovery-" + configuredEngineId;
        }
        return fallbackRecoveryLeaseOwnerId;
    }

    private Duration effectiveDistributedLeaseTtl() {
        return distributedLeaseTtl != null && !distributedLeaseTtl.isZero() && !distributedLeaseTtl.isNegative()
                ? distributedLeaseTtl
                : Duration.ofMinutes(2);
    }

    private Duration effectiveDistributedLeaseRenewInterval() {
        Duration ttl = effectiveDistributedLeaseTtl();
        if (distributedLeaseRenewInterval != null
                && !distributedLeaseRenewInterval.isZero()
                && !distributedLeaseRenewInterval.isNegative()
                && distributedLeaseRenewInterval.compareTo(ttl) < 0) {
            return distributedLeaseRenewInterval;
        }
        Duration halfTtl = ttl.dividedBy(2);
        return !halfTtl.isZero() && !halfTtl.isNegative() ? halfTtl : ttl;
    }

    private static String normalizedText(String value) {
        return value != null && !value.isBlank() ? value.trim() : null;
    }

    private int effectiveMaxConcurrentRuns() {
        return maxConcurrentRuns > 0 ? maxConcurrentRuns : 4;
    }

    private ReplayConsistencyMode replayConsistencyMode() {
        if (replayConsistencyMode == null || replayConsistencyMode.isBlank()) {
            return ReplayConsistencyMode.BEST_EFFORT;
        }
        String normalized = replayConsistencyMode.trim()
                .replace('-', '_')
                .toUpperCase();
        return switch (normalized) {
            case "DISABLED", "OFF", "FALSE" -> ReplayConsistencyMode.DISABLED;
            case "STRICT", "REQUIRED", "TRUE" -> ReplayConsistencyMode.STRICT;
            case "BEST_EFFORT", "BESTEFFORT" -> ReplayConsistencyMode.BEST_EFFORT;
            default -> {
                LOG.warn("Unknown gamelan.recovery.replay-consistency-mode={}, using best-effort",
                        replayConsistencyMode);
                yield ReplayConsistencyMode.BEST_EFFORT;
            }
        };
    }

    private RecoveryMetrics recoveryMetrics() {
        MeterRegistry registry = meterRegistry;
        if (recoveryMetrics == null || recoveryMetrics.registry != registry) {
            recoveryMetrics = new RecoveryMetrics(registry);
        }
        return recoveryMetrics;
    }

    private String safeRunId(WorkflowRun run) {
        return run != null && run.getId() != null ? run.getId().value() : "<unknown>";
    }

    private RecoveryRunKey recoveryRunKey(WorkflowRun run) {
        if (run == null || run.getId() == null || run.getTenantId() == null) {
            return null;
        }
        return new RecoveryRunKey(run.getId(), run.getTenantId());
    }

    private record RecoveryRunKey(WorkflowRunId runId, TenantId tenantId) {
    }

    private record RecoveryRunScan(
            List<WorkflowRun> runs,
            int scanPages,
            int scanCandidates,
            int duplicateCandidates,
            int invalidCandidates,
            int cursorStalls,
            boolean limitReached) {
        private RecoveryRunScan {
            runs = runs != null ? List.copyOf(runs) : List.of();
            scanPages = Math.max(0, scanPages);
            scanCandidates = Math.max(0, scanCandidates);
            duplicateCandidates = Math.max(0, duplicateCandidates);
            invalidCandidates = Math.max(0, invalidCandidates);
            cursorStalls = Math.max(0, cursorStalls);
        }
    }

    private static final class RecoveryLeaseContext {
        private WorkflowRecoveryLease lease;
        private Instant renewedAt;

        private RecoveryLeaseContext(WorkflowRecoveryLease lease) {
            update(lease);
        }

        private WorkflowRecoveryLease lease() {
            return lease;
        }

        private boolean shouldRenew(Instant current, Duration interval) {
            return lease != null
                    && renewedAt != null
                    && current != null
                    && interval != null
                    && !renewedAt.plus(interval).isAfter(current);
        }

        private void update(WorkflowRecoveryLease lease) {
            this.lease = lease;
            this.renewedAt = lease != null && lease.acquiredAt() != null ? lease.acquiredAt() : Instant.EPOCH;
        }
    }

    private record RecoveryLeaseAttempt(RecoveryLeaseContext context, RecoverySweepSkipReason skipReason) {
        private WorkflowRecoveryLease lease() {
            return context != null ? context.lease() : null;
        }

        private static RecoveryLeaseAttempt notRequired() {
            return new RecoveryLeaseAttempt(null, null);
        }

        private static RecoveryLeaseAttempt acquired(WorkflowRecoveryLease lease) {
            return new RecoveryLeaseAttempt(new RecoveryLeaseContext(lease), null);
        }

        private static RecoveryLeaseAttempt skipped(RecoverySweepSkipReason reason) {
            return new RecoveryLeaseAttempt(null, reason);
        }

        private static RecoveryLeaseAttempt from(WorkflowRecoveryLease lease) {
            if (lease == null) {
                return skipped(RecoverySweepSkipReason.LEASE_REPOSITORY_UNAVAILABLE);
            }
            if (!lease.acquired()) {
                return skipped(RecoverySweepSkipReason.LEASE_HELD);
            }
            return acquired(lease);
        }
    }

    private record RetrySchedule(NodeId nodeId, int attempt, Duration delay) {
    }

    private enum ReplayConsistencyMode {
        DISABLED,
        BEST_EFFORT,
        STRICT
    }

    public enum LockedRecoveryOutcome {
        NO_WORK("no_work"),
        DEFERRED_ACTIVE("deferred_active"),
        SKIPPED_AFTER_LOCK("skipped_after_lock");

        private final String metricName;

        LockedRecoveryOutcome(String metricName) {
            this.metricName = metricName;
        }

        String metricName() {
            return metricName;
        }
    }

    public enum RecoverySweepSkipReason {
        ALREADY_RUNNING("already_running"),
        LEASE_HELD("lease_held"),
        LEASE_REPOSITORY_UNAVAILABLE("lease_repository_unavailable");

        private final String metricName;

        RecoverySweepSkipReason(String metricName) {
            this.metricName = metricName;
        }

        String metricName() {
            return metricName;
        }
    }

    private enum RecoveryLeaseOperation {
        ACQUIRE("acquire"),
        RENEW("renew"),
        RELEASE("release");

        private final String metricName;

        RecoveryLeaseOperation(String metricName) {
            this.metricName = metricName;
        }

        String metricName() {
            return metricName;
        }
    }

    private enum RecoveryLeaseOutcome {
        SUCCESS("success"),
        HELD("held"),
        REPOSITORY_UNAVAILABLE("repository_unavailable"),
        FAILURE("failure");

        private final String metricName;

        RecoveryLeaseOutcome(String metricName) {
            this.metricName = metricName;
        }

        String metricName() {
            return metricName;
        }
    }

    private static final class RecoveryMetrics {
        private final MeterRegistry registry;
        private final Counter sweepSuccesses;
        private final Counter sweepFailures;
        private final Map<RecoverySweepSkipReason, Counter> sweepSkippedCounters;
        private final Map<RecoverySweepSkipReason, Timer> sweepSkippedDurationTimers;
        private final Map<RecoveryLeaseOperation, Map<RecoveryLeaseOutcome, Counter>> leaseOperationCounters;
        private final Timer sweepSuccessDuration;
        private final Timer sweepFailureDuration;
        private final Counter scannedRuns;
        private final Counter dueRetries;
        private final Counter staleRecoveredExecutions;
        private final Counter retryWakeups;
        private final Counter mutatedRuns;
        private final Counter failedRuns;
        private final Counter noWorkRuns;
        private final Counter deferredActiveRuns;
        private final Counter skippedAfterLockRuns;
        private final Counter replayDrifts;
        private final Counter replayUnavailable;
        private final Counter replayMismatches;
        private final Counter wakeups;
        private final Counter scanPages;
        private final Counter scanCandidates;
        private final Counter duplicateScanCandidates;
        private final Counter invalidScanCandidates;
        private final Counter scanCursorStalls;
        private final Counter scanLimitReached;
        private final Map<WorkflowReplayConsistencyChecker.Status, Counter> replayBlockCounters;
        private final Map<LockedRecoveryOutcome, Map<RunStatus, Counter>> lockedOutcomeStatusCounters;

        private RecoveryMetrics(MeterRegistry registry) {
            this.registry = registry;
            this.sweepSuccesses = counter(registry, "gamelan.recovery.sweeps", "Recovery sweeps", "outcome", "success");
            this.sweepFailures = counter(registry, "gamelan.recovery.sweeps", "Recovery sweeps", "outcome", "failure");
            this.sweepSkippedCounters = sweepSkippedCounters(registry);
            this.sweepSkippedDurationTimers = sweepSkippedDurationTimers(registry);
            this.leaseOperationCounters = leaseOperationCounters(registry);
            this.sweepSuccessDuration = timer(
                    registry,
                    "gamelan.recovery.sweep.duration",
                    "Recovery sweep duration",
                    "outcome",
                    "success");
            this.sweepFailureDuration = timer(
                    registry,
                    "gamelan.recovery.sweep.duration",
                    "Recovery sweep duration",
                    "outcome",
                    "failure");
            this.scannedRuns = counter(registry, "gamelan.recovery.runs.scanned", "Workflow runs scanned by recovery");
            this.dueRetries = counter(registry, "gamelan.recovery.retries.due", "Due retries found by recovery");
            this.staleRecoveredExecutions = counter(
                    registry,
                    "gamelan.recovery.executions.stale_recovered",
                    "Stale node executions recovered by recovery");
            this.retryWakeups = counter(
                    registry,
                    "gamelan.recovery.retry_wakeups.scheduled",
                    "Retry wake-ups scheduled by recovery");
            this.mutatedRuns = counter(registry, "gamelan.recovery.runs.mutated", "Workflow runs mutated by recovery");
            this.failedRuns = counter(registry, "gamelan.recovery.runs.failed", "Workflow run recoveries that failed");
            this.noWorkRuns = counter(
                    registry,
                    "gamelan.recovery.runs.no_work",
                    "Workflow runs locked by recovery with no recovery work needed");
            this.deferredActiveRuns = counter(
                    registry,
                    "gamelan.recovery.runs.deferred_active",
                    "Active workflow runs intentionally deferred by recovery because their status is not RUNNING");
            this.skippedAfterLockRuns = counter(
                    registry,
                    "gamelan.recovery.runs.skipped_after_lock",
                    "Workflow runs skipped because state changed before recovery lock processing");
            this.replayDrifts = counter(
                    registry,
                    "gamelan.recovery.replay.drifts",
                    "Workflow runs skipped because replay drift was detected");
            this.replayUnavailable = counter(
                    registry,
                    "gamelan.recovery.replay.unavailable",
                    "Workflow runs skipped because replay consistency dependencies were unavailable");
            this.replayMismatches = counter(
                    registry,
                    "gamelan.recovery.replay.mismatches",
                    "Replay consistency mismatches detected during recovery");
            this.wakeups = counter(
                    registry,
                    "gamelan.recovery.wakeups.requested",
                    "Workflow run wake-ups requested by recovery");
            this.scanPages = counter(
                    registry,
                    "gamelan.recovery.scan.pages",
                    "Recovery scan pages read from the workflow run repository");
            this.scanCandidates = counter(
                    registry,
                    "gamelan.recovery.scan.candidates",
                    "Recovery scan candidate rows read from the workflow run repository");
            this.duplicateScanCandidates = counter(
                    registry,
                    "gamelan.recovery.scan.duplicates",
                    "Duplicate recovery scan candidates skipped before run recovery");
            this.invalidScanCandidates = counter(
                    registry,
                    "gamelan.recovery.scan.invalid_candidates",
                    "Invalid recovery scan candidates skipped before run recovery");
            this.scanCursorStalls = counter(
                    registry,
                    "gamelan.recovery.scan.cursor_stalls",
                    "Recovery scans stopped because a repository cursor did not advance");
            this.scanLimitReached = counter(
                    registry,
                    "gamelan.recovery.scan.limit_reached",
                    "Recovery sweeps that reached the configured scanned-run limit");
            this.replayBlockCounters = replayBlockCounters(registry);
            this.lockedOutcomeStatusCounters = lockedOutcomeStatusCounters(registry);
        }

        private Timer.Sample startSweep() {
            return registry != null ? Timer.start(registry) : null;
        }

        private void recordSweepSuccess(RecoverySweepResult result, Timer.Sample sample) {
            if (registry == null) {
                return;
            }
            RecoverySweepResult safeResult = result != null ? result : RecoverySweepResult.empty();
            sweepSuccesses.increment();
            increment(scannedRuns, safeResult.scannedRuns());
            increment(dueRetries, safeResult.dueRetries());
            increment(staleRecoveredExecutions, safeResult.staleExecutions());
            increment(retryWakeups, safeResult.retryWakeups());
            increment(mutatedRuns, safeResult.mutatedRuns());
            increment(failedRuns, safeResult.failedRuns());
            increment(noWorkRuns, safeResult.noWorkRuns());
            increment(deferredActiveRuns, safeResult.deferredActiveRuns());
            increment(skippedAfterLockRuns, safeResult.skippedAfterLockRuns());
            incrementLockedOutcomeStatusCounters(safeResult);
            increment(replayDrifts, safeResult.replayDrifts());
            increment(replayUnavailable, safeResult.replayUnavailable());
            incrementReplayBlockCounters(safeResult);
            increment(replayMismatches, safeResult.replayMismatchCount());
            increment(wakeups, safeResult.wakeups());
            increment(scanPages, safeResult.scanPages());
            increment(scanCandidates, safeResult.scanCandidates());
            increment(duplicateScanCandidates, safeResult.duplicateScanCandidates());
            increment(invalidScanCandidates, safeResult.invalidScanCandidates());
            increment(scanCursorStalls, safeResult.scanCursorStalls());
            increment(scanLimitReached, safeResult.scanLimitReached() ? 1 : 0);
            stop(sample, sweepSuccessDuration);
        }

        private void recordSweepFailure(Timer.Sample sample) {
            if (registry == null) {
                return;
            }
            sweepFailures.increment();
            stop(sample, sweepFailureDuration);
        }

        private void recordSweepSkipped(RecoverySweepResult result, Timer.Sample sample) {
            if (registry == null || result == null) {
                return;
            }
            RecoverySweepSkipReason durationReason = null;
            for (Map.Entry<RecoverySweepSkipReason, Integer> entry : result.skipReasonCounts().entrySet()) {
                int count = entry.getValue() != null ? entry.getValue() : 0;
                increment(sweepSkippedCounters.get(entry.getKey()), count);
                if (durationReason == null && count > 0) {
                    durationReason = entry.getKey();
                }
            }
            if (durationReason != null) {
                stop(sample, sweepSkippedDurationTimers.get(durationReason));
            }
        }

        private void recordLeaseOperation(RecoveryLeaseOperation operation, RecoveryLeaseOutcome outcome) {
            if (registry == null || operation == null || outcome == null) {
                return;
            }
            Map<RecoveryLeaseOutcome, Counter> outcomeCounters = leaseOperationCounters.get(operation);
            Counter counter = outcomeCounters != null ? outcomeCounters.get(outcome) : null;
            increment(counter, 1);
        }

        private static Counter counter(MeterRegistry registry, String name, String description, String... tags) {
            return registry != null
                    ? Counter.builder(name).description(description).tags(tags).register(registry)
                    : null;
        }

        private void incrementReplayBlockCounters(RecoverySweepResult result) {
            if (result == null) {
                return;
            }
            for (Map.Entry<WorkflowReplayConsistencyChecker.Status, Integer> entry
                    : result.replayBlockStatusCounts().entrySet()) {
                increment(replayBlockCounters.get(entry.getKey()), entry.getValue() != null ? entry.getValue() : 0);
            }
        }

        private void incrementLockedOutcomeStatusCounters(RecoverySweepResult result) {
            if (result == null) {
                return;
            }
            for (Map.Entry<LockedRecoveryOutcome, Map<RunStatus, Integer>> outcomeEntry
                    : result.lockedOutcomeStatusCounts().entrySet()) {
                if (outcomeEntry == null || outcomeEntry.getKey() == null || outcomeEntry.getValue() == null) {
                    continue;
                }
                for (Map.Entry<RunStatus, Integer> statusEntry : outcomeEntry.getValue().entrySet()) {
                    if (statusEntry == null || statusEntry.getKey() == null) {
                        continue;
                    }
                    Map<RunStatus, Counter> statusCounters = lockedOutcomeStatusCounters.get(outcomeEntry.getKey());
                    Counter counter = statusCounters != null ? statusCounters.get(statusEntry.getKey()) : null;
                    increment(counter, statusEntry.getValue() != null ? statusEntry.getValue() : 0);
                }
            }
        }

        private static Map<RecoverySweepSkipReason, Counter> sweepSkippedCounters(MeterRegistry registry) {
            if (registry == null) {
                return Map.of();
            }
            EnumMap<RecoverySweepSkipReason, Counter> counters =
                    new EnumMap<>(RecoverySweepSkipReason.class);
            for (RecoverySweepSkipReason reason : RecoverySweepSkipReason.values()) {
                counters.put(
                        reason,
                        counter(
                                registry,
                                "gamelan.recovery.sweeps.skipped",
                                "Recovery sweeps skipped before scan execution",
                                "reason",
                                reason.metricName()));
            }
            return Map.copyOf(counters);
        }

        private static Map<RecoverySweepSkipReason, Timer> sweepSkippedDurationTimers(MeterRegistry registry) {
            if (registry == null) {
                return Map.of();
            }
            EnumMap<RecoverySweepSkipReason, Timer> timers =
                    new EnumMap<>(RecoverySweepSkipReason.class);
            for (RecoverySweepSkipReason reason : RecoverySweepSkipReason.values()) {
                timers.put(
                        reason,
                        timer(
                                registry,
                                "gamelan.recovery.sweep.skipped.duration",
                                "Recovery skipped sweep duration",
                                "reason",
                                reason.metricName()));
            }
            return Map.copyOf(timers);
        }

        private static Map<RecoveryLeaseOperation, Map<RecoveryLeaseOutcome, Counter>> leaseOperationCounters(
                MeterRegistry registry) {
            if (registry == null) {
                return Map.of();
            }
            EnumMap<RecoveryLeaseOperation, Map<RecoveryLeaseOutcome, Counter>> counters =
                    new EnumMap<>(RecoveryLeaseOperation.class);
            for (RecoveryLeaseOperation operation : RecoveryLeaseOperation.values()) {
                EnumMap<RecoveryLeaseOutcome, Counter> outcomeCounters =
                        new EnumMap<>(RecoveryLeaseOutcome.class);
                for (RecoveryLeaseOutcome outcome : RecoveryLeaseOutcome.values()) {
                    outcomeCounters.put(
                            outcome,
                            counter(
                                    registry,
                                    "gamelan.recovery.lease.operations",
                                    "Recovery lease operations by operation and outcome",
                                    "operation",
                                    operation.metricName(),
                                    "outcome",
                                    outcome.metricName()));
                }
                counters.put(operation, Map.copyOf(outcomeCounters));
            }
            return Map.copyOf(counters);
        }

        private static Map<WorkflowReplayConsistencyChecker.Status, Counter> replayBlockCounters(
                MeterRegistry registry) {
            if (registry == null) {
                return Map.of();
            }
            EnumMap<WorkflowReplayConsistencyChecker.Status, Counter> counters =
                    new EnumMap<>(WorkflowReplayConsistencyChecker.Status.class);
            counters.put(
                    WorkflowReplayConsistencyChecker.Status.DRIFT,
                    counter(
                            registry,
                            "gamelan.recovery.replay.blocks",
                            "Workflow runs skipped by replay consistency status",
                            "status",
                            WorkflowReplayConsistencyChecker.Status.DRIFT.name().toLowerCase()));
            counters.put(
                    WorkflowReplayConsistencyChecker.Status.UNAVAILABLE,
                    counter(
                            registry,
                            "gamelan.recovery.replay.blocks",
                            "Workflow runs skipped by replay consistency status",
                            "status",
                            WorkflowReplayConsistencyChecker.Status.UNAVAILABLE.name().toLowerCase()));
            return Map.copyOf(counters);
        }

        private static Map<LockedRecoveryOutcome, Map<RunStatus, Counter>> lockedOutcomeStatusCounters(
                MeterRegistry registry) {
            if (registry == null) {
                return Map.of();
            }
            EnumMap<LockedRecoveryOutcome, Map<RunStatus, Counter>> counters =
                    new EnumMap<>(LockedRecoveryOutcome.class);
            for (LockedRecoveryOutcome outcome : LockedRecoveryOutcome.values()) {
                EnumMap<RunStatus, Counter> statusCounters = new EnumMap<>(RunStatus.class);
                for (RunStatus status : RunStatus.values()) {
                    statusCounters.put(
                            status,
                            counter(
                                    registry,
                                    "gamelan.recovery.runs.locked_status",
                                    "Workflow recovery locked-run outcomes by status",
                                    "outcome",
                                    outcome.metricName(),
                                    "status",
                                    status.name().toLowerCase()));
                }
                counters.put(outcome, Map.copyOf(statusCounters));
            }
            return Map.copyOf(counters);
        }

        private static Timer timer(MeterRegistry registry, String name, String description, String... tags) {
            return registry != null
                    ? Timer.builder(name).description(description).tags(tags).register(registry)
                    : null;
        }

        private static void increment(Counter counter, int amount) {
            if (counter != null && amount > 0) {
                counter.increment(amount);
            }
        }

        private static void stop(Timer.Sample sample, Timer timer) {
            if (sample != null && timer != null) {
                sample.stop(timer);
            }
        }
    }

    public record RecoveryRunResult(
            WorkflowRunId runId,
            TenantId tenantId,
            int scannedRuns,
            int dueRetries,
            int staleExecutions,
            int retryWakeups,
            boolean mutated,
            boolean failed,
            WorkflowReplayConsistencyChecker.Status replayStatus,
            List<WorkflowReplayConsistencyChecker.Mismatch> replayMismatches,
            boolean shouldWakeRun,
            boolean noWork,
            boolean deferredActive,
            boolean skippedAfterLock,
            RunStatus lockedStatus) {
        public RecoveryRunResult(
                WorkflowRunId runId,
                TenantId tenantId,
                int scannedRuns,
                int dueRetries,
                int staleExecutions,
                int retryWakeups,
                boolean mutated,
                boolean failed,
                WorkflowReplayConsistencyChecker.Status replayStatus,
                List<WorkflowReplayConsistencyChecker.Mismatch> replayMismatches,
                boolean shouldWakeRun) {
            this(
                    runId,
                    tenantId,
                    scannedRuns,
                    dueRetries,
                    staleExecutions,
                    retryWakeups,
                    mutated,
                    failed,
                    replayStatus,
                    replayMismatches,
                    shouldWakeRun,
                    false,
                    false,
                    false,
                    null);
        }

        public RecoveryRunResult(
                WorkflowRunId runId,
                TenantId tenantId,
                int scannedRuns,
                int dueRetries,
                int staleExecutions,
                int retryWakeups,
                boolean mutated,
                boolean failed,
                boolean replayDrift,
                boolean replayUnavailable,
                List<WorkflowReplayConsistencyChecker.Mismatch> replayMismatches,
                boolean shouldWakeRun) {
            this(
                    runId,
                    tenantId,
                    scannedRuns,
                    dueRetries,
                    staleExecutions,
                    retryWakeups,
                    mutated,
                    failed,
                    replayStatus(replayDrift, replayUnavailable, replayMismatches),
                    replayMismatches,
                    shouldWakeRun,
                    false,
                    false,
                    false,
                    null);
        }

        public RecoveryRunResult {
            List<WorkflowReplayConsistencyChecker.Mismatch> safeMismatches =
                    replayMismatches != null ? List.copyOf(replayMismatches) : List.of();
            WorkflowReplayConsistencyChecker.Status safeStatus =
                    replayStatus != null ? replayStatus : replayStatus(false, false, safeMismatches);
            if (safeStatus == WorkflowReplayConsistencyChecker.Status.CONSISTENT && !safeMismatches.isEmpty()) {
                safeStatus = WorkflowReplayConsistencyChecker.Status.DRIFT;
            }
            replayStatus = safeStatus;
            replayMismatches = safeMismatches;
        }

        public boolean replayDrift() {
            return replayStatus == WorkflowReplayConsistencyChecker.Status.DRIFT;
        }

        public boolean replayUnavailable() {
            return replayStatus == WorkflowReplayConsistencyChecker.Status.UNAVAILABLE;
        }

        public boolean replayBlocked() {
            return replayStatus != WorkflowReplayConsistencyChecker.Status.CONSISTENT;
        }

        public ReplayBlockSummary replayBlockSummary() {
            return ReplayBlockSummary.from(replayStatus, replayMismatches);
        }

        static RecoveryRunResult empty() {
            return new RecoveryRunResult(
                    null,
                    null,
                    0,
                    0,
                    0,
                    0,
                    false,
                    false,
                    WorkflowReplayConsistencyChecker.Status.CONSISTENT,
                    List.of(),
                    false);
        }

        static RecoveryRunResult failed(WorkflowRun run) {
            return new RecoveryRunResult(
                    run != null ? run.getId() : null,
                    run != null ? run.getTenantId() : null,
                    run != null ? 1 : 0,
                    0,
                    0,
                    0,
                    false,
                    true,
                    WorkflowReplayConsistencyChecker.Status.CONSISTENT,
                    List.of(),
                    false);
        }

        static RecoveryRunResult noWork(WorkflowRun run) {
            return new RecoveryRunResult(
                    run != null ? run.getId() : null,
                    run != null ? run.getTenantId() : null,
                    run != null ? 1 : 0,
                    0,
                    0,
                    0,
                    false,
                    false,
                    WorkflowReplayConsistencyChecker.Status.CONSISTENT,
                    List.of(),
                    false,
                    true,
                    false,
                    false,
                    statusOf(run));
        }

        static RecoveryRunResult deferredActive(WorkflowRun run) {
            return new RecoveryRunResult(
                    run != null ? run.getId() : null,
                    run != null ? run.getTenantId() : null,
                    run != null ? 1 : 0,
                    0,
                    0,
                    0,
                    false,
                    false,
                    WorkflowReplayConsistencyChecker.Status.CONSISTENT,
                    List.of(),
                    false,
                    false,
                    run != null,
                    false,
                    statusOf(run));
        }

        static RecoveryRunResult skippedAfterLock(WorkflowRun run) {
            return new RecoveryRunResult(
                    run != null ? run.getId() : null,
                    run != null ? run.getTenantId() : null,
                    run != null ? 1 : 0,
                    0,
                    0,
                    0,
                    false,
                    false,
                    WorkflowReplayConsistencyChecker.Status.CONSISTENT,
                    List.of(),
                    false,
                    false,
                    false,
                    run != null,
                    statusOf(run));
        }

        static RecoveryRunResult replayBlocked(
                WorkflowRun run,
                WorkflowReplayConsistencyChecker.Report replayConsistency) {
            return new RecoveryRunResult(
                    run != null ? run.getId() : null,
                    run != null ? run.getTenantId() : null,
                    run != null ? 1 : 0,
                    0,
                    0,
                    0,
                    false,
                    false,
                    replayConsistency != null ? replayConsistency.status() : WorkflowReplayConsistencyChecker.Status.DRIFT,
                    replayConsistency != null ? replayConsistency.mismatches() : List.of(),
                    false);
        }

        private static WorkflowReplayConsistencyChecker.Status replayStatus(
                boolean replayDrift,
                boolean replayUnavailable,
                List<WorkflowReplayConsistencyChecker.Mismatch> replayMismatches) {
            if (replayUnavailable) {
                return WorkflowReplayConsistencyChecker.Status.UNAVAILABLE;
            }
            if (replayDrift || (replayMismatches != null && !replayMismatches.isEmpty())) {
                return WorkflowReplayConsistencyChecker.Status.DRIFT;
            }
            return WorkflowReplayConsistencyChecker.Status.CONSISTENT;
        }

        private static RunStatus statusOf(WorkflowRun run) {
            return run != null ? run.getStatus() : null;
        }
    }

    public record ReplayBlockSummary(
            WorkflowReplayConsistencyChecker.Status status,
            int mismatchCount,
            List<String> mismatchFields,
            boolean truncated) {
        private static final int MAX_MISMATCH_FIELDS = 16;

        public ReplayBlockSummary {
            status = status != null ? status : WorkflowReplayConsistencyChecker.Status.CONSISTENT;
            mismatchCount = Math.max(0, mismatchCount);
            mismatchFields = mismatchFields != null ? List.copyOf(mismatchFields) : List.of();
        }

        public boolean blocked() {
            return status != WorkflowReplayConsistencyChecker.Status.CONSISTENT;
        }

        static ReplayBlockSummary from(
                WorkflowReplayConsistencyChecker.Status status,
                List<WorkflowReplayConsistencyChecker.Mismatch> mismatches) {
            List<WorkflowReplayConsistencyChecker.Mismatch> safeMismatches =
                    mismatches != null ? mismatches : List.of();
            WorkflowReplayConsistencyChecker.Status safeStatus =
                    status != null ? status
                            : safeMismatches.isEmpty()
                                    ? WorkflowReplayConsistencyChecker.Status.CONSISTENT
                                    : WorkflowReplayConsistencyChecker.Status.DRIFT;
            List<String> fields = new ArrayList<>();
            boolean truncated = false;
            for (WorkflowReplayConsistencyChecker.Mismatch mismatch : safeMismatches) {
                if (mismatch == null || mismatch.field() == null || mismatch.field().isBlank()) {
                    continue;
                }
                if (fields.contains(mismatch.field())) {
                    continue;
                }
                if (fields.size() >= MAX_MISMATCH_FIELDS) {
                    truncated = true;
                    continue;
                }
                fields.add(mismatch.field());
            }
            return new ReplayBlockSummary(safeStatus, safeMismatches.size(), fields, truncated);
        }
    }

    public record RecoverySweepResult(
            int scannedRuns,
            int dueRetries,
            int staleExecutions,
            int retryWakeups,
            int mutatedRuns,
            int failedRuns,
            int noWorkRuns,
            int deferredActiveRuns,
            int skippedAfterLockRuns,
            int replayDrifts,
            int replayUnavailable,
            int replayMismatchCount,
            int wakeups,
            int scanPages,
            int scanCandidates,
            int duplicateScanCandidates,
            int invalidScanCandidates,
            int scanCursorStalls,
            boolean scanLimitReached,
            Map<LockedRecoveryOutcome, Map<RunStatus, Integer>> lockedOutcomeStatusCounts,
            Map<WorkflowReplayConsistencyChecker.Status, Map<String, Integer>> replayBlockFieldCounts,
            int skippedSweeps,
            Map<RecoverySweepSkipReason, Integer> skipReasonCounts) {

        public RecoverySweepResult(
                int scannedRuns,
                int dueRetries,
                int staleExecutions,
                int retryWakeups,
                int mutatedRuns,
                int failedRuns,
                int replayDrifts,
                int replayUnavailable,
                int replayMismatchCount,
                int wakeups) {
            this(
                    scannedRuns,
                    dueRetries,
                    staleExecutions,
                    retryWakeups,
                    mutatedRuns,
                    failedRuns,
                    0,
                    0,
                    0,
                    replayDrifts,
                    replayUnavailable,
                    replayMismatchCount,
                    wakeups,
                    0,
                    0,
                    0,
                    0,
                    0,
                    false,
                    Map.of(),
                    Map.of());
        }

        public RecoverySweepResult(
                int scannedRuns,
                int dueRetries,
                int staleExecutions,
                int retryWakeups,
                int mutatedRuns,
                int failedRuns,
                int noWorkRuns,
                int deferredActiveRuns,
                int skippedAfterLockRuns,
                int replayDrifts,
                int replayUnavailable,
                int replayMismatchCount,
                int wakeups,
                int scanPages,
                int scanCandidates,
                int duplicateScanCandidates,
                int invalidScanCandidates,
                int scanCursorStalls,
                boolean scanLimitReached,
                Map<LockedRecoveryOutcome, Map<RunStatus, Integer>> lockedOutcomeStatusCounts,
                Map<WorkflowReplayConsistencyChecker.Status, Map<String, Integer>> replayBlockFieldCounts) {
            this(
                    scannedRuns,
                    dueRetries,
                    staleExecutions,
                    retryWakeups,
                    mutatedRuns,
                    failedRuns,
                    noWorkRuns,
                    deferredActiveRuns,
                    skippedAfterLockRuns,
                    replayDrifts,
                    replayUnavailable,
                    replayMismatchCount,
                    wakeups,
                    scanPages,
                    scanCandidates,
                    duplicateScanCandidates,
                    invalidScanCandidates,
                    scanCursorStalls,
                    scanLimitReached,
                    lockedOutcomeStatusCounts,
                    replayBlockFieldCounts,
                    0,
                    Map.of());
        }

        public RecoverySweepResult {
            scanPages = Math.max(0, scanPages);
            scanCandidates = Math.max(0, scanCandidates);
            duplicateScanCandidates = Math.max(0, duplicateScanCandidates);
            invalidScanCandidates = Math.max(0, invalidScanCandidates);
            scanCursorStalls = Math.max(0, scanCursorStalls);
            noWorkRuns = Math.max(0, noWorkRuns);
            deferredActiveRuns = Math.max(0, deferredActiveRuns);
            skippedAfterLockRuns = Math.max(0, skippedAfterLockRuns);
            lockedOutcomeStatusCounts = copyLockedOutcomeStatusCounts(lockedOutcomeStatusCounts);
            replayBlockFieldCounts = copyReplayBlockFieldCounts(replayBlockFieldCounts);
            skippedSweeps = Math.max(0, skippedSweeps);
            skipReasonCounts = copySkipReasonCounts(skipReasonCounts);
        }

        static RecoverySweepResult empty() {
            return new RecoverySweepResult(0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }

        static RecoverySweepResult skipped(RecoverySweepSkipReason reason) {
            if (reason == null) {
                return empty();
            }
            return new RecoverySweepResult(
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    false,
                    Map.of(),
                    Map.of(),
                    1,
                    Map.of(reason, 1));
        }

        public RecoverySweepResult withScanLimitReached(boolean limitReached) {
            if (!limitReached || scanLimitReached) {
                return this;
            }
            return new RecoverySweepResult(
                    scannedRuns,
                    dueRetries,
                    staleExecutions,
                    retryWakeups,
                    mutatedRuns,
                    failedRuns,
                    noWorkRuns,
                    deferredActiveRuns,
                    skippedAfterLockRuns,
                    replayDrifts,
                    replayUnavailable,
                    replayMismatchCount,
                    wakeups,
                    scanPages,
                    scanCandidates,
                    duplicateScanCandidates,
                    invalidScanCandidates,
                    scanCursorStalls,
                    true,
                    lockedOutcomeStatusCounts,
                    replayBlockFieldCounts,
                    skippedSweeps,
                    skipReasonCounts);
        }

        RecoverySweepResult withScanTelemetry(RecoveryRunScan scan) {
            if (scan == null) {
                return this;
            }
            return new RecoverySweepResult(
                    scannedRuns,
                    dueRetries,
                    staleExecutions,
                    retryWakeups,
                    mutatedRuns,
                    failedRuns,
                    noWorkRuns,
                    deferredActiveRuns,
                    skippedAfterLockRuns,
                    replayDrifts,
                    replayUnavailable,
                    replayMismatchCount,
                    wakeups,
                    scan.scanPages(),
                    scan.scanCandidates(),
                    scan.duplicateCandidates(),
                    scan.invalidCandidates(),
                    scan.cursorStalls(),
                    scanLimitReached || scan.limitReached(),
                    lockedOutcomeStatusCounts,
                    replayBlockFieldCounts,
                    skippedSweeps,
                    skipReasonCounts);
        }

        public int replayBlockedRuns() {
            return replayDrifts + replayUnavailable;
        }

        public Map<WorkflowReplayConsistencyChecker.Status, Integer> replayBlockStatusCounts() {
            EnumMap<WorkflowReplayConsistencyChecker.Status, Integer> counts =
                    new EnumMap<>(WorkflowReplayConsistencyChecker.Status.class);
            putIfPositive(counts, WorkflowReplayConsistencyChecker.Status.DRIFT, replayDrifts);
            putIfPositive(counts, WorkflowReplayConsistencyChecker.Status.UNAVAILABLE, replayUnavailable);
            return Map.copyOf(counts);
        }

        static RecoverySweepResult fromRunResults(List<RecoveryRunResult> results) {
            if (results == null || results.isEmpty()) {
                return empty();
            }
            int scannedRuns = 0;
            int dueRetries = 0;
            int staleExecutions = 0;
            int retryWakeups = 0;
            int mutatedRuns = 0;
            int failedRuns = 0;
            int noWorkRuns = 0;
            int deferredActiveRuns = 0;
            int skippedAfterLockRuns = 0;
            int replayDrifts = 0;
            int replayUnavailable = 0;
            int replayMismatchCount = 0;
            int wakeups = 0;
            EnumMap<LockedRecoveryOutcome, Map<RunStatus, Integer>> lockedOutcomeStatusCounts =
                    new EnumMap<>(LockedRecoveryOutcome.class);
            EnumMap<WorkflowReplayConsistencyChecker.Status, Map<String, Integer>> replayBlockFieldCounts =
                    new EnumMap<>(WorkflowReplayConsistencyChecker.Status.class);
            for (RecoveryRunResult result : results) {
                if (result == null) {
                    continue;
                }
                scannedRuns += result.scannedRuns();
                dueRetries += result.dueRetries();
                staleExecutions += result.staleExecutions();
                retryWakeups += result.retryWakeups();
                mutatedRuns += result.mutated() ? 1 : 0;
                failedRuns += result.failed() ? 1 : 0;
                noWorkRuns += result.noWork() ? 1 : 0;
                deferredActiveRuns += result.deferredActive() ? 1 : 0;
                skippedAfterLockRuns += result.skippedAfterLock() ? 1 : 0;
                replayDrifts += result.replayDrift() ? 1 : 0;
                replayUnavailable += result.replayUnavailable() ? 1 : 0;
                replayMismatchCount += result.replayMismatches().size();
                addLockedOutcomeStatusCounts(lockedOutcomeStatusCounts, result);
                addReplayBlockFieldCounts(replayBlockFieldCounts, result.replayBlockSummary());
                wakeups += result.shouldWakeRun() ? 1 : 0;
            }
            return new RecoverySweepResult(
                    scannedRuns,
                    dueRetries,
                    staleExecutions,
                    retryWakeups,
                    mutatedRuns,
                    failedRuns,
                    noWorkRuns,
                    deferredActiveRuns,
                    skippedAfterLockRuns,
                    replayDrifts,
                    replayUnavailable,
                    replayMismatchCount,
                    wakeups,
                    0,
                    0,
                    0,
                    0,
                    0,
                    false,
                    lockedOutcomeStatusCounts,
                    replayBlockFieldCounts);
        }

        RecoverySweepResult plus(RecoverySweepResult other) {
            if (other == null) {
                return this;
            }
            return new RecoverySweepResult(
                    scannedRuns + other.scannedRuns,
                    dueRetries + other.dueRetries,
                    staleExecutions + other.staleExecutions,
                    retryWakeups + other.retryWakeups,
                    mutatedRuns + other.mutatedRuns,
                    failedRuns + other.failedRuns,
                    noWorkRuns + other.noWorkRuns,
                    deferredActiveRuns + other.deferredActiveRuns,
                    skippedAfterLockRuns + other.skippedAfterLockRuns,
                    replayDrifts + other.replayDrifts,
                    replayUnavailable + other.replayUnavailable,
                    replayMismatchCount + other.replayMismatchCount,
                    wakeups + other.wakeups,
                    scanPages + other.scanPages,
                    scanCandidates + other.scanCandidates,
                    duplicateScanCandidates + other.duplicateScanCandidates,
                    invalidScanCandidates + other.invalidScanCandidates,
                    scanCursorStalls + other.scanCursorStalls,
                    scanLimitReached || other.scanLimitReached,
                    mergeLockedOutcomeStatusCounts(lockedOutcomeStatusCounts, other.lockedOutcomeStatusCounts),
                    mergeReplayBlockFieldCounts(replayBlockFieldCounts, other.replayBlockFieldCounts),
                    skippedSweeps + other.skippedSweeps,
                    mergeSkipReasonCounts(skipReasonCounts, other.skipReasonCounts));
        }

        private static void putIfPositive(
                Map<WorkflowReplayConsistencyChecker.Status, Integer> counts,
                WorkflowReplayConsistencyChecker.Status status,
                int count) {
            if (count > 0) {
                counts.put(status, count);
            }
        }

        private static void addReplayBlockFieldCounts(
                Map<WorkflowReplayConsistencyChecker.Status, Map<String, Integer>> counts,
                ReplayBlockSummary summary) {
            if (counts == null || summary == null || !summary.blocked() || summary.mismatchFields().isEmpty()) {
                return;
            }
            Map<String, Integer> fields = counts.computeIfAbsent(summary.status(), ignored -> new LinkedHashMap<>());
            for (String field : summary.mismatchFields()) {
                if (field != null && !field.isBlank()) {
                    fields.merge(field, 1, Integer::sum);
                }
            }
        }

        private static void addLockedOutcomeStatusCounts(
                Map<LockedRecoveryOutcome, Map<RunStatus, Integer>> counts,
                RecoveryRunResult result) {
            if (counts == null || result == null || result.lockedStatus() == null) {
                return;
            }
            LockedRecoveryOutcome outcome = lockedOutcome(result);
            if (outcome == null) {
                return;
            }
            counts.computeIfAbsent(outcome, ignored -> new EnumMap<>(RunStatus.class))
                    .merge(result.lockedStatus(), 1, Integer::sum);
        }

        private static LockedRecoveryOutcome lockedOutcome(RecoveryRunResult result) {
            if (result.noWork()) {
                return LockedRecoveryOutcome.NO_WORK;
            }
            if (result.deferredActive()) {
                return LockedRecoveryOutcome.DEFERRED_ACTIVE;
            }
            if (result.skippedAfterLock()) {
                return LockedRecoveryOutcome.SKIPPED_AFTER_LOCK;
            }
            return null;
        }

        private static Map<LockedRecoveryOutcome, Map<RunStatus, Integer>> mergeLockedOutcomeStatusCounts(
                Map<LockedRecoveryOutcome, Map<RunStatus, Integer>> first,
                Map<LockedRecoveryOutcome, Map<RunStatus, Integer>> second) {
            EnumMap<LockedRecoveryOutcome, Map<RunStatus, Integer>> merged =
                    mutableLockedOutcomeStatusCounts(first);
            if (second != null) {
                for (Map.Entry<LockedRecoveryOutcome, Map<RunStatus, Integer>> outcomeEntry : second.entrySet()) {
                    if (outcomeEntry == null || outcomeEntry.getKey() == null || outcomeEntry.getValue() == null) {
                        continue;
                    }
                    Map<RunStatus, Integer> statuses =
                            merged.computeIfAbsent(outcomeEntry.getKey(), ignored -> new EnumMap<>(RunStatus.class));
                    for (Map.Entry<RunStatus, Integer> statusEntry : outcomeEntry.getValue().entrySet()) {
                        if (statusEntry == null || statusEntry.getKey() == null) {
                            continue;
                        }
                        int count = statusEntry.getValue() != null ? statusEntry.getValue() : 0;
                        if (count > 0) {
                            statuses.merge(statusEntry.getKey(), count, Integer::sum);
                        }
                    }
                }
            }
            return immutableLockedOutcomeStatusCounts(merged);
        }

        private static Map<LockedRecoveryOutcome, Map<RunStatus, Integer>> copyLockedOutcomeStatusCounts(
                Map<LockedRecoveryOutcome, Map<RunStatus, Integer>> counts) {
            return immutableLockedOutcomeStatusCounts(mutableLockedOutcomeStatusCounts(counts));
        }

        private static EnumMap<LockedRecoveryOutcome, Map<RunStatus, Integer>> mutableLockedOutcomeStatusCounts(
                Map<LockedRecoveryOutcome, Map<RunStatus, Integer>> counts) {
            EnumMap<LockedRecoveryOutcome, Map<RunStatus, Integer>> copy =
                    new EnumMap<>(LockedRecoveryOutcome.class);
            if (counts == null) {
                return copy;
            }
            for (Map.Entry<LockedRecoveryOutcome, Map<RunStatus, Integer>> outcomeEntry : counts.entrySet()) {
                if (outcomeEntry == null || outcomeEntry.getKey() == null || outcomeEntry.getValue() == null) {
                    continue;
                }
                EnumMap<RunStatus, Integer> statuses = new EnumMap<>(RunStatus.class);
                for (Map.Entry<RunStatus, Integer> statusEntry : outcomeEntry.getValue().entrySet()) {
                    if (statusEntry == null || statusEntry.getKey() == null) {
                        continue;
                    }
                    int count = statusEntry.getValue() != null ? statusEntry.getValue() : 0;
                    if (count > 0) {
                        statuses.put(statusEntry.getKey(), count);
                    }
                }
                if (!statuses.isEmpty()) {
                    copy.put(outcomeEntry.getKey(), statuses);
                }
            }
            return copy;
        }

        private static Map<LockedRecoveryOutcome, Map<RunStatus, Integer>> immutableLockedOutcomeStatusCounts(
                Map<LockedRecoveryOutcome, Map<RunStatus, Integer>> counts) {
            if (counts == null || counts.isEmpty()) {
                return Map.of();
            }
            EnumMap<LockedRecoveryOutcome, Map<RunStatus, Integer>> immutable =
                    new EnumMap<>(LockedRecoveryOutcome.class);
            for (Map.Entry<LockedRecoveryOutcome, Map<RunStatus, Integer>> outcomeEntry : counts.entrySet()) {
                if (outcomeEntry == null || outcomeEntry.getKey() == null || outcomeEntry.getValue() == null
                        || outcomeEntry.getValue().isEmpty()) {
                    continue;
                }
                immutable.put(outcomeEntry.getKey(), Map.copyOf(outcomeEntry.getValue()));
            }
            return Map.copyOf(immutable);
        }

        private static Map<RecoverySweepSkipReason, Integer> mergeSkipReasonCounts(
                Map<RecoverySweepSkipReason, Integer> first,
                Map<RecoverySweepSkipReason, Integer> second) {
            EnumMap<RecoverySweepSkipReason, Integer> merged = mutableSkipReasonCounts(first);
            if (second != null) {
                for (Map.Entry<RecoverySweepSkipReason, Integer> entry : second.entrySet()) {
                    if (entry == null || entry.getKey() == null) {
                        continue;
                    }
                    int count = entry.getValue() != null ? entry.getValue() : 0;
                    if (count > 0) {
                        merged.merge(entry.getKey(), count, Integer::sum);
                    }
                }
            }
            return immutableSkipReasonCounts(merged);
        }

        private static Map<RecoverySweepSkipReason, Integer> copySkipReasonCounts(
                Map<RecoverySweepSkipReason, Integer> counts) {
            return immutableSkipReasonCounts(mutableSkipReasonCounts(counts));
        }

        private static EnumMap<RecoverySweepSkipReason, Integer> mutableSkipReasonCounts(
                Map<RecoverySweepSkipReason, Integer> counts) {
            EnumMap<RecoverySweepSkipReason, Integer> copy =
                    new EnumMap<>(RecoverySweepSkipReason.class);
            if (counts == null) {
                return copy;
            }
            for (Map.Entry<RecoverySweepSkipReason, Integer> entry : counts.entrySet()) {
                if (entry == null || entry.getKey() == null) {
                    continue;
                }
                int count = entry.getValue() != null ? entry.getValue() : 0;
                if (count > 0) {
                    copy.put(entry.getKey(), count);
                }
            }
            return copy;
        }

        private static Map<RecoverySweepSkipReason, Integer> immutableSkipReasonCounts(
                Map<RecoverySweepSkipReason, Integer> counts) {
            if (counts == null || counts.isEmpty()) {
                return Map.of();
            }
            return Map.copyOf(counts);
        }

        private static Map<WorkflowReplayConsistencyChecker.Status, Map<String, Integer>> mergeReplayBlockFieldCounts(
                Map<WorkflowReplayConsistencyChecker.Status, Map<String, Integer>> first,
                Map<WorkflowReplayConsistencyChecker.Status, Map<String, Integer>> second) {
            EnumMap<WorkflowReplayConsistencyChecker.Status, Map<String, Integer>> merged =
                    mutableReplayBlockFieldCounts(first);
            if (second != null) {
                for (Map.Entry<WorkflowReplayConsistencyChecker.Status, Map<String, Integer>> statusEntry
                        : second.entrySet()) {
                    if (statusEntry == null || statusEntry.getKey() == null || statusEntry.getValue() == null) {
                        continue;
                    }
                    Map<String, Integer> fields =
                            merged.computeIfAbsent(statusEntry.getKey(), ignored -> new LinkedHashMap<>());
                    for (Map.Entry<String, Integer> fieldEntry : statusEntry.getValue().entrySet()) {
                        if (fieldEntry == null || fieldEntry.getKey() == null || fieldEntry.getKey().isBlank()) {
                            continue;
                        }
                        int count = fieldEntry.getValue() != null ? fieldEntry.getValue() : 0;
                        if (count > 0) {
                            fields.merge(fieldEntry.getKey(), count, Integer::sum);
                        }
                    }
                }
            }
            return copyReplayBlockFieldCounts(merged);
        }

        private static EnumMap<WorkflowReplayConsistencyChecker.Status, Map<String, Integer>>
                mutableReplayBlockFieldCounts(
                        Map<WorkflowReplayConsistencyChecker.Status, Map<String, Integer>> source) {
            EnumMap<WorkflowReplayConsistencyChecker.Status, Map<String, Integer>> copy =
                    new EnumMap<>(WorkflowReplayConsistencyChecker.Status.class);
            if (source == null) {
                return copy;
            }
            for (Map.Entry<WorkflowReplayConsistencyChecker.Status, Map<String, Integer>> statusEntry
                    : source.entrySet()) {
                if (statusEntry == null || statusEntry.getKey() == null || statusEntry.getValue() == null) {
                    continue;
                }
                Map<String, Integer> fields = new LinkedHashMap<>();
                for (Map.Entry<String, Integer> fieldEntry : statusEntry.getValue().entrySet()) {
                    if (fieldEntry == null || fieldEntry.getKey() == null || fieldEntry.getKey().isBlank()) {
                        continue;
                    }
                    int count = fieldEntry.getValue() != null ? fieldEntry.getValue() : 0;
                    if (count > 0) {
                        fields.put(fieldEntry.getKey(), count);
                    }
                }
                if (!fields.isEmpty()) {
                    copy.put(statusEntry.getKey(), fields);
                }
            }
            return copy;
        }

        private static Map<WorkflowReplayConsistencyChecker.Status, Map<String, Integer>>
                copyReplayBlockFieldCounts(
                        Map<WorkflowReplayConsistencyChecker.Status, Map<String, Integer>> source) {
            EnumMap<WorkflowReplayConsistencyChecker.Status, Map<String, Integer>> copy =
                    mutableReplayBlockFieldCounts(source);
            if (copy.isEmpty()) {
                return Map.of();
            }
            EnumMap<WorkflowReplayConsistencyChecker.Status, Map<String, Integer>> frozen =
                    new EnumMap<>(WorkflowReplayConsistencyChecker.Status.class);
            for (Map.Entry<WorkflowReplayConsistencyChecker.Status, Map<String, Integer>> entry : copy.entrySet()) {
                frozen.put(entry.getKey(), Map.copyOf(entry.getValue()));
            }
            return Map.copyOf(frozen);
        }
    }
}
