package tech.kayys.gamelan.runtime.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.enterprise.inject.Instance;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupDeadLetterAuditEvent.Operation;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupDeadLetterAuditEvent.Outcome;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupDeadLetterAuditSink;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupDeadLetterAuditSink.AuditPurgePolicy;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupDeadLetterAuditSink.AuditPurgeResult;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupDeadLetterAuditSink.AuditQuery;
import tech.kayys.gamelan.engine.repository.WorkflowRecoveryLease;
import tech.kayys.gamelan.engine.repository.WorkflowRecoveryLeaseRepository;

@ApplicationScoped
public class WorkflowRunWakeupDeadLetterAuditRetentionService {

    private static final String RETENTION_LEASE_NAME = "maintenance:workflow-wakeup-dead-letter-audit-retention";
    private static final Duration DEFAULT_LEASE_TTL = Duration.ofMinutes(5);

    private final AtomicBoolean retentionRunning = new AtomicBoolean();
    private final AtomicReference<AuditRetentionRunResult> lastResult = new AtomicReference<>();
    private final AtomicReference<AuditRetentionLease> activeLease = new AtomicReference<>();
    private final String fallbackLeaseOwnerId = "gamelan-audit-retention-" + UUID.randomUUID();

    @Inject
    WorkflowRunWakeupDeadLetterAuditSink auditSink;

    @Inject
    Instance<WorkflowRecoveryLeaseRepository> leaseRepositories;

    @Inject
    Instance<MeterRegistry> meterRegistries;

    WorkflowRecoveryLeaseRepository leaseRepository;
    MeterRegistry meterRegistry;

    @ConfigProperty(name = "gamelan.workflow.wakeup.dead-letter-audit.retention.enabled", defaultValue = "false")
    boolean enabled;

    @ConfigProperty(name = "gamelan.workflow.wakeup.dead-letter-audit.retention.older-than")
    Optional<Duration> olderThan = Optional.empty();

    @ConfigProperty(name = "gamelan.workflow.wakeup.dead-letter-audit.retention.retain-latest", defaultValue = "-1")
    int retainLatest;

    @ConfigProperty(name = "gamelan.workflow.wakeup.dead-letter-audit.retention.dry-run", defaultValue = "true")
    boolean dryRun;

    @ConfigProperty(name = "gamelan.workflow.wakeup.dead-letter-audit.retention.query.limit", defaultValue = "1000")
    int queryLimit;

    @ConfigProperty(name = "gamelan.workflow.wakeup.dead-letter-audit.retention.query.operation")
    Optional<String> operation = Optional.empty();

    @ConfigProperty(name = "gamelan.workflow.wakeup.dead-letter-audit.retention.query.outcome")
    Optional<String> outcome = Optional.empty();

    @ConfigProperty(name = "gamelan.workflow.wakeup.dead-letter-audit.retention.query.intent-id")
    Optional<String> intentId = Optional.empty();

    @ConfigProperty(name = "gamelan.workflow.wakeup.dead-letter-audit.retention.query.run-id")
    Optional<String> runId = Optional.empty();

    @ConfigProperty(name = "gamelan.workflow.wakeup.dead-letter-audit.retention.query.tenant-id")
    Optional<String> tenantId = Optional.empty();

    @ConfigProperty(name = "gamelan.workflow.wakeup.dead-letter-audit.retention.query.dry-run")
    Optional<Boolean> dryRunFilter = Optional.empty();

    @ConfigProperty(name = "gamelan.workflow.wakeup.dead-letter-audit.retention.lease.owner-id", defaultValue = "")
    String leaseOwnerId;

    @ConfigProperty(name = "gamelan.workflow.wakeup.dead-letter-audit.retention.lease.ttl", defaultValue = "5m")
    Duration leaseTtl;

    public AuditRetentionPolicy configuredPolicy(Boolean dryRunOverride) {
        return new AuditRetentionPolicy(
                enabled,
                configuredQuery(),
                olderThan.orElse(null),
                retainLatest,
                dryRunOverride != null ? dryRunOverride : dryRun);
    }

    public AuditRetentionStatus status(Boolean dryRunOverride) {
        return new AuditRetentionStatus(
                retentionRunning.get(),
                Instant.now(),
                configuredPolicy(dryRunOverride),
                lastResult.get(),
                activeLease.get());
    }

    public Uni<AuditRetentionRunResult> runConfiguredRetention(boolean force, Boolean dryRunOverride) {
        AuditRetentionPolicy policy = configuredPolicy(dryRunOverride);
        Instant evaluatedAt = Instant.now();
        RetentionMetrics metrics = retentionMetrics();
        Timer.Sample durationSample = metrics.start();
        if (!policy.enabled() && !force) {
            return Uni.createFrom().item(remember(metrics.record(AuditRetentionRunResult.skipped(
                    policy,
                    "retention_disabled",
                    evaluatedAt), durationSample)));
        }
        if (!policy.hasRetentionCriteria()) {
            return Uni.createFrom().item(remember(metrics.record(AuditRetentionRunResult.skipped(
                    policy,
                    "retention_not_configured",
                    evaluatedAt), durationSample)));
        }
        if (auditSink == null) {
            return Uni.createFrom().item(remember(metrics.record(AuditRetentionRunResult.skipped(
                    policy,
                    "audit_sink_unavailable",
                    evaluatedAt), durationSample)));
        }
        if (!retentionRunning.compareAndSet(false, true)) {
            return Uni.createFrom().item(remember(metrics.record(AuditRetentionRunResult.skipped(
                    policy,
                    "retention_already_running",
                    evaluatedAt), durationSample)));
        }
        return acquireMaintenanceLease(evaluatedAt)
                .flatMap(lease -> runWithLease(policy, lease, metrics, durationSample))
                .onFailure().invoke(error -> {
                    retentionRunning.set(false);
                    activeLease.set(null);
                    if (!failureRecordedSince(evaluatedAt)) {
                        remember(metrics.record(AuditRetentionRunResult.failed(
                                policy,
                                errorMessage(error),
                                Instant.now(),
                                AuditRetentionLease.notRequired(effectiveLeaseOwnerId())), durationSample));
                    }
                });
    }

    private Uni<AuditRetentionRunResult> runWithLease(
            AuditRetentionPolicy policy,
            AuditRetentionLease lease,
            RetentionMetrics metrics,
            Timer.Sample durationSample) {
        if (lease.required() && !lease.acquired()) {
            retentionRunning.set(false);
            return Uni.createFrom().item(remember(metrics.record(AuditRetentionRunResult.skipped(
                    policy,
                    "retention_lease_unavailable",
                    Instant.now(),
                    lease), durationSample)));
        }

        activeLease.set(lease.acquired() ? lease : null);
        try {
            return auditSink.purge(policy.toPurgePolicy())
                    .map(result -> remember(metrics.record(AuditRetentionRunResult.executed(
                            policy,
                            result,
                            Instant.now(),
                            lease), durationSample)))
                    .onFailure().invoke(error -> remember(metrics.record(AuditRetentionRunResult.failed(
                            policy,
                            errorMessage(error),
                            Instant.now(),
                            lease), durationSample)))
                    .eventually(() -> releaseMaintenanceLease(lease)
                            .eventually(() -> {
                                activeLease.set(null);
                                retentionRunning.set(false);
                                return Uni.createFrom().voidItem();
                            }));
        } catch (RuntimeException error) {
            activeLease.set(null);
            retentionRunning.set(false);
            remember(metrics.record(
                    AuditRetentionRunResult.failed(policy, errorMessage(error), Instant.now(), lease),
                    durationSample));
            throw error;
        }
    }

    private Uni<AuditRetentionLease> acquireMaintenanceLease(Instant now) {
        WorkflowRecoveryLeaseRepository repository = leaseRepository();
        String ownerId = effectiveLeaseOwnerId();
        if (repository == null) {
            return Uni.createFrom().item(AuditRetentionLease.notRequired(ownerId));
        }
        return repository.tryAcquireRecoveryLease(
                RETENTION_LEASE_NAME,
                ownerId,
                positiveDuration(leaseTtl, DEFAULT_LEASE_TTL),
                now)
                .map(AuditRetentionLease::from);
    }

    private Uni<Void> releaseMaintenanceLease(AuditRetentionLease lease) {
        if (lease == null || !lease.acquired()) {
            return Uni.createFrom().voidItem();
        }
        WorkflowRecoveryLeaseRepository repository = leaseRepository();
        if (repository == null) {
            return Uni.createFrom().voidItem();
        }
        WorkflowRecoveryLease recoveryLease = WorkflowRecoveryLease.acquired(
                lease.leaseName(),
                lease.ownerId(),
                lease.acquiredAt(),
                lease.expiresAt());
        return repository.releaseRecoveryLease(recoveryLease)
                .onFailure().recoverWithNull();
    }

    private AuditQuery configuredQuery() {
        return new AuditQuery(
                queryLimit,
                enumValue(operation, Operation.class),
                enumValue(outcome, Outcome.class),
                optionalText(intentId),
                optionalText(runId),
                optionalText(tenantId),
                dryRunFilter.orElse(null),
                null,
                null);
    }

    private static String optionalText(Optional<String> value) {
        return value
                .map(String::trim)
                .filter(text -> !text.isBlank())
                .orElse(null);
    }

    private static <E extends Enum<E>> E enumValue(Optional<String> value, Class<E> type) {
        String text = optionalText(value);
        return text != null ? Enum.valueOf(type, text.toUpperCase(Locale.ROOT)) : null;
    }

    private WorkflowRecoveryLeaseRepository leaseRepository() {
        if (leaseRepository != null) {
            return leaseRepository;
        }
        if (leaseRepositories == null || leaseRepositories.isUnsatisfied() || leaseRepositories.isAmbiguous()) {
            return null;
        }
        return leaseRepositories.get();
    }

    private RetentionMetrics retentionMetrics() {
        return new RetentionMetrics(meterRegistry());
    }

    private MeterRegistry meterRegistry() {
        if (meterRegistry != null) {
            return meterRegistry;
        }
        if (meterRegistries == null || meterRegistries.isUnsatisfied() || meterRegistries.isAmbiguous()) {
            return null;
        }
        return meterRegistries.get();
    }

    private String effectiveLeaseOwnerId() {
        String configuredOwnerId = optionalText(Optional.ofNullable(leaseOwnerId));
        return configuredOwnerId != null ? configuredOwnerId : fallbackLeaseOwnerId;
    }

    private AuditRetentionRunResult remember(AuditRetentionRunResult result) {
        lastResult.set(result);
        return result;
    }

    private boolean failureRecordedSince(Instant startedAt) {
        AuditRetentionRunResult result = lastResult.get();
        return result != null
                && result.error() != null
                && startedAt != null
                && !result.evaluatedAt().isBefore(startedAt);
    }

    private static String errorMessage(Throwable error) {
        if (error == null) {
            return null;
        }
        return error.getMessage() != null ? error.getMessage() : error.getClass().getName();
    }

    private static Duration positiveDuration(Duration value, Duration fallback) {
        return value != null && !value.isZero() && !value.isNegative() ? value : fallback;
    }

    private static final class RetentionMetrics {
        private final MeterRegistry registry;

        private RetentionMetrics(MeterRegistry registry) {
            this.registry = registry;
        }

        private Timer.Sample start() {
            return registry != null ? Timer.start(registry) : null;
        }

        private AuditRetentionRunResult record(AuditRetentionRunResult result, Timer.Sample durationSample) {
            if (registry == null || result == null) {
                return result;
            }

            Tags tags = Tags.from(result);
            counter(
                    "gamelan.workflow.wakeup.dead_letter.audit.retention.runs",
                    "Workflow wake-up dead-letter audit retention runs",
                    tags)
                    .increment();
            stop(durationSample, timer(
                    "gamelan.workflow.wakeup.dead_letter.audit.retention.duration",
                    "Workflow wake-up dead-letter audit retention duration",
                    tags));
            if (result.executed()) {
                incrementRecords("selected", result.purge().selected(), result.policy().dryRun());
                incrementRecords("purged", result.purge().purged(), result.policy().dryRun());
            }
            return result;
        }

        private void incrementRecords(String kind, int amount, boolean dryRun) {
            if (amount <= 0) {
                return;
            }
            Counter.builder("gamelan.workflow.wakeup.dead_letter.audit.retention.records")
                    .description("Workflow wake-up dead-letter audit retention selected and purged records")
                    .tag("kind", kind)
                    .tag("dry_run", Boolean.toString(dryRun))
                    .register(registry)
                    .increment(amount);
        }

        private Counter counter(String name, String description, Tags tags) {
            return Counter.builder(name)
                    .description(description)
                    .tag("outcome", tags.outcome())
                    .tag("reason", tags.reason())
                    .tag("dry_run", tags.dryRun())
                    .tag("lease", tags.lease())
                    .register(registry);
        }

        private Timer timer(String name, String description, Tags tags) {
            return Timer.builder(name)
                    .description(description)
                    .tag("outcome", tags.outcome())
                    .tag("reason", tags.reason())
                    .tag("dry_run", tags.dryRun())
                    .tag("lease", tags.lease())
                    .register(registry);
        }

        private static void stop(Timer.Sample sample, Timer timer) {
            if (sample != null && timer != null) {
                sample.stop(timer);
            }
        }

        private record Tags(
                String outcome,
                String reason,
                String dryRun,
                String lease) {

            private static Tags from(AuditRetentionRunResult result) {
                String outcome = result.executed()
                        ? "executed"
                        : result.error() != null ? "failed" : "skipped";
                String reason = result.executed()
                        ? "executed"
                        : result.error() != null ? "failure" : result.skippedReason();
                return new Tags(
                        outcome,
                        reason != null ? reason : "unknown",
                        Boolean.toString(result.policy().dryRun()),
                        leaseTag(result.lease()));
            }

            private static String leaseTag(AuditRetentionLease lease) {
                if (lease == null || !lease.required()) {
                    return "not_required";
                }
                return lease.acquired() ? "acquired" : "unavailable";
            }
        }
    }

    public record AuditRetentionPolicy(
            boolean enabled,
            AuditQuery query,
            Duration olderThan,
            int retainLatest,
            boolean dryRun) {

        public AuditRetentionPolicy {
            query = query != null ? query : AuditQuery.all();
            if (olderThan != null && olderThan.isNegative()) {
                throw new IllegalArgumentException("Audit retention olderThan cannot be negative");
            }
            if (retainLatest < -1) {
                throw new IllegalArgumentException("Audit retention retainLatest cannot be lower than -1");
            }
        }

        public boolean hasRetentionCriteria() {
            return olderThan != null || retainLatest >= 0;
        }

        public AuditPurgePolicy toPurgePolicy() {
            return new AuditPurgePolicy(query, olderThan, retainLatest, dryRun);
        }
    }

    public record AuditRetentionLease(
            boolean required,
            boolean acquired,
            String leaseName,
            String ownerId,
            Instant acquiredAt,
            Instant expiresAt) {

        public AuditRetentionLease {
            ownerId = ownerId != null && !ownerId.isBlank() ? ownerId.trim() : "";
            if (required) {
                leaseName = Objects.requireNonNull(leaseName, "Audit retention leaseName cannot be null");
            }
            if (required && acquired) {
                acquiredAt = Objects.requireNonNull(
                        acquiredAt,
                        "Audit retention lease acquiredAt cannot be null for acquired leases");
                expiresAt = Objects.requireNonNull(
                        expiresAt,
                        "Audit retention lease expiresAt cannot be null for acquired leases");
            }
        }

        public static AuditRetentionLease from(WorkflowRecoveryLease lease) {
            if (lease == null) {
                return notAcquired(RETENTION_LEASE_NAME, "");
            }
            return new AuditRetentionLease(
                    true,
                    lease.acquired(),
                    lease.leaseName(),
                    lease.ownerId(),
                    lease.acquiredAt(),
                    lease.expiresAt());
        }

        public static AuditRetentionLease notRequired(String ownerId) {
            return new AuditRetentionLease(false, false, null, ownerId, null, null);
        }

        private static AuditRetentionLease notAcquired(String leaseName, String ownerId) {
            return new AuditRetentionLease(true, false, leaseName, ownerId, null, null);
        }
    }

    public record AuditRetentionRunResult(
            boolean executed,
            String skippedReason,
            String error,
            Instant evaluatedAt,
            AuditRetentionPolicy policy,
            AuditPurgeResult purge,
            AuditRetentionLease lease) {

        public AuditRetentionRunResult {
            skippedReason = optionalText(Optional.ofNullable(skippedReason));
            error = optionalText(Optional.ofNullable(error));
            evaluatedAt = Objects.requireNonNull(evaluatedAt, "Audit retention evaluatedAt cannot be null");
            policy = Objects.requireNonNull(policy, "Audit retention policy cannot be null");
            purge = purge != null ? purge : AuditPurgeResult.empty(policy.dryRun());
            lease = lease != null ? lease : AuditRetentionLease.notRequired("");
        }

        public static AuditRetentionRunResult skipped(
                AuditRetentionPolicy policy,
                String reason,
                Instant evaluatedAt) {
            return skipped(policy, reason, evaluatedAt, AuditRetentionLease.notRequired(""));
        }

        public static AuditRetentionRunResult skipped(
                AuditRetentionPolicy policy,
                String reason,
                Instant evaluatedAt,
                AuditRetentionLease lease) {
            return new AuditRetentionRunResult(
                    false,
                    reason,
                    null,
                    evaluatedAt,
                    policy,
                    AuditPurgeResult.empty(policy != null && policy.dryRun()),
                    lease);
        }

        public static AuditRetentionRunResult executed(
                AuditRetentionPolicy policy,
                AuditPurgeResult purge,
                Instant evaluatedAt) {
            return executed(policy, purge, evaluatedAt, AuditRetentionLease.notRequired(""));
        }

        public static AuditRetentionRunResult executed(
                AuditRetentionPolicy policy,
                AuditPurgeResult purge,
                Instant evaluatedAt,
                AuditRetentionLease lease) {
            return new AuditRetentionRunResult(
                    true,
                    null,
                    null,
                    evaluatedAt,
                    policy,
                    purge,
                    lease);
        }

        public static AuditRetentionRunResult failed(
                AuditRetentionPolicy policy,
                String error,
                Instant evaluatedAt) {
            return failed(policy, error, evaluatedAt, AuditRetentionLease.notRequired(""));
        }

        public static AuditRetentionRunResult failed(
                AuditRetentionPolicy policy,
                String error,
                Instant evaluatedAt,
                AuditRetentionLease lease) {
            return new AuditRetentionRunResult(
                    false,
                    null,
                    error,
                    evaluatedAt,
                    policy,
                    AuditPurgeResult.empty(policy != null && policy.dryRun()),
                    lease);
        }
    }

    public record AuditRetentionStatus(
            boolean running,
            Instant observedAt,
            AuditRetentionPolicy policy,
            AuditRetentionRunResult lastResult,
            AuditRetentionLease activeLease) {

        public AuditRetentionStatus {
            observedAt = Objects.requireNonNull(observedAt, "Audit retention status observedAt cannot be null");
            policy = Objects.requireNonNull(policy, "Audit retention status policy cannot be null");
        }
    }
}
