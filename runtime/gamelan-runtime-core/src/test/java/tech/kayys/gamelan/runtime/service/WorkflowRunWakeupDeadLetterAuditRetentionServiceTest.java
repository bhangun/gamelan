package tech.kayys.gamelan.runtime.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupDeadLetterAuditEvent;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupDeadLetterAuditEvent.Operation;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupDeadLetterAuditEvent.Outcome;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupDeadLetterAuditSink;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupDeadLetterAuditSink.AuditPurgePolicy;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupDeadLetterAuditSink.AuditPurgeResult;
import tech.kayys.gamelan.engine.repository.WorkflowRecoveryLease;
import tech.kayys.gamelan.engine.repository.WorkflowRecoveryLeaseRepository;
import tech.kayys.gamelan.runtime.service.WorkflowRunWakeupDeadLetterAuditRetentionService.AuditRetentionRunResult;
import tech.kayys.gamelan.runtime.service.WorkflowRunWakeupDeadLetterAuditRetentionService.AuditRetentionStatus;

class WorkflowRunWakeupDeadLetterAuditRetentionServiceTest {

    @Test
    void disabledRetentionSkipsWithoutCallingAuditSink() {
        RecordingAuditSink sink = new RecordingAuditSink();
        WorkflowRunWakeupDeadLetterAuditRetentionService service = service(sink);
        service.enabled = false;
        service.olderThan = Optional.of(Duration.ofDays(7));

        AuditRetentionRunResult result = service.runConfiguredRetention(false, null).await().indefinitely();

        assertEquals(false, result.executed());
        assertEquals("retention_disabled", result.skippedReason());
        assertEquals(true, result.policy().dryRun());
        assertNull(sink.purgePolicy);
        assertEquals(false, service.status(null).running());
        assertEquals(result, service.status(null).lastResult());
    }

    @Test
    void enabledRetentionWithoutCriteriaSkipsWithoutCallingAuditSink() {
        RecordingAuditSink sink = new RecordingAuditSink();
        WorkflowRunWakeupDeadLetterAuditRetentionService service = service(sink);
        service.enabled = true;
        service.olderThan = Optional.empty();
        service.retainLatest = -1;

        AuditRetentionRunResult result = service.runConfiguredRetention(false, null).await().indefinitely();

        assertEquals(false, result.executed());
        assertEquals("retention_not_configured", result.skippedReason());
        assertNull(sink.purgePolicy);
        assertEquals(result, service.status(null).lastResult());
    }

    @Test
    void configuredRetentionBuildsFilteredPurgePolicy() {
        RecordingAuditSink sink = new RecordingAuditSink();
        sink.purgeResult = new AuditPurgeResult(3, 3, false, List.of("audit-1", "audit-2", "audit-3"));
        WorkflowRunWakeupDeadLetterAuditRetentionService service = service(sink);
        service.enabled = true;
        service.olderThan = Optional.of(Duration.ofDays(30));
        service.retainLatest = 200;
        service.dryRun = true;
        service.queryLimit = 500;
        service.operation = Optional.of(" purge ");
        service.outcome = Optional.of(" succeeded ");
        service.intentId = Optional.of(" intent-1 ");
        service.runId = Optional.of(" run-1 ");
        service.tenantId = Optional.of(" tenant-1 ");
        service.dryRunFilter = Optional.of(false);

        AuditRetentionRunResult result = service.runConfiguredRetention(false, false).await().indefinitely();

        assertEquals(true, result.executed());
        assertNull(result.skippedReason());
        assertEquals(sink.purgeResult, result.purge());
        assertEquals(Operation.PURGE, sink.purgePolicy.query().operation());
        assertEquals(Outcome.SUCCEEDED, sink.purgePolicy.query().outcome());
        assertEquals("intent-1", sink.purgePolicy.query().intentId());
        assertEquals("run-1", sink.purgePolicy.query().runId());
        assertEquals("tenant-1", sink.purgePolicy.query().tenantId());
        assertEquals(false, sink.purgePolicy.query().dryRun());
        assertEquals(500, sink.purgePolicy.query().limit());
        assertEquals(Duration.ofDays(30), sink.purgePolicy.olderThan());
        assertEquals(200, sink.purgePolicy.retainLatest());
        assertEquals(false, sink.purgePolicy.dryRun());
        assertEquals(false, service.status(null).running());
        assertEquals(result, service.status(null).lastResult());
    }

    @Test
    void forceRunsConfiguredRetentionEvenWhenDisabled() {
        RecordingAuditSink sink = new RecordingAuditSink();
        WorkflowRunWakeupDeadLetterAuditRetentionService service = service(sink);
        service.enabled = false;
        service.olderThan = Optional.of(Duration.ofHours(1));

        AuditRetentionRunResult result = service.runConfiguredRetention(true, null).await().indefinitely();

        assertEquals(true, result.executed());
        assertEquals(Duration.ofHours(1), sink.purgePolicy.olderThan());
    }

    @Test
    void durableLeaseUnavailableSkipsWithoutCallingAuditSink() {
        RecordingAuditSink sink = new RecordingAuditSink();
        RecordingLeaseRepository leaseRepository = new RecordingLeaseRepository();
        leaseRepository.nextAcquire = WorkflowRecoveryLease.notAcquired(
                "maintenance:workflow-wakeup-dead-letter-audit-retention",
                "owner-a");
        WorkflowRunWakeupDeadLetterAuditRetentionService service = service(sink);
        service.leaseRepository = leaseRepository;
        service.leaseOwnerId = "owner-a";
        service.enabled = true;
        service.olderThan = Optional.of(Duration.ofDays(1));

        AuditRetentionRunResult result = service.runConfiguredRetention(false, null).await().indefinitely();

        assertFalse(result.executed());
        assertEquals("retention_lease_unavailable", result.skippedReason());
        assertTrue(result.lease().required());
        assertFalse(result.lease().acquired());
        assertNull(sink.purgePolicy);
        assertNull(leaseRepository.releasedLease);
    }

    @Test
    void durableLeaseIsReleasedAfterSuccessfulRun() {
        RecordingAuditSink sink = new RecordingAuditSink();
        RecordingLeaseRepository leaseRepository = new RecordingLeaseRepository();
        Instant acquiredAt = Instant.parse("2026-06-08T00:00:00Z");
        leaseRepository.nextAcquire = WorkflowRecoveryLease.acquired(
                "maintenance:workflow-wakeup-dead-letter-audit-retention",
                "owner-a",
                acquiredAt,
                acquiredAt.plus(Duration.ofMinutes(5)));
        WorkflowRunWakeupDeadLetterAuditRetentionService service = service(sink);
        service.leaseRepository = leaseRepository;
        service.leaseOwnerId = "owner-a";
        service.enabled = true;
        service.olderThan = Optional.of(Duration.ofDays(1));

        AuditRetentionRunResult result = service.runConfiguredRetention(false, null).await().indefinitely();

        assertTrue(result.executed());
        assertTrue(result.lease().required());
        assertTrue(result.lease().acquired());
        assertEquals("owner-a", result.lease().ownerId());
        assertEquals(leaseRepository.nextAcquire.leaseName(), leaseRepository.releasedLease.leaseName());
        assertEquals("owner-a", leaseRepository.releasedLease.ownerId());
        assertNull(service.status(null).activeLease());
    }

    @Test
    void metricsRecordExecutedRunAndRecordCounts() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        RecordingAuditSink sink = new RecordingAuditSink();
        sink.purgeResult = new AuditPurgeResult(3, 2, false, List.of("audit-1", "audit-2", "audit-3"));
        WorkflowRunWakeupDeadLetterAuditRetentionService service = service(sink);
        service.meterRegistry = meterRegistry;
        service.enabled = true;
        service.olderThan = Optional.of(Duration.ofDays(1));
        service.dryRun = false;

        service.runConfiguredRetention(false, null).await().indefinitely();

        assertEquals(1.0, counter(
                meterRegistry,
                "gamelan.workflow.wakeup.dead_letter.audit.retention.runs",
                "outcome", "executed",
                "reason", "executed",
                "dry_run", "false",
                "lease", "not_required"));
        assertEquals(1, timerCount(
                meterRegistry,
                "gamelan.workflow.wakeup.dead_letter.audit.retention.duration",
                "outcome", "executed",
                "reason", "executed",
                "dry_run", "false",
                "lease", "not_required"));
        assertEquals(3.0, counter(
                meterRegistry,
                "gamelan.workflow.wakeup.dead_letter.audit.retention.records",
                "kind", "selected",
                "dry_run", "false"));
        assertEquals(2.0, counter(
                meterRegistry,
                "gamelan.workflow.wakeup.dead_letter.audit.retention.records",
                "kind", "purged",
                "dry_run", "false"));
    }

    @Test
    void metricsRecordLeaseUnavailableSkip() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        RecordingAuditSink sink = new RecordingAuditSink();
        RecordingLeaseRepository leaseRepository = new RecordingLeaseRepository();
        leaseRepository.nextAcquire = WorkflowRecoveryLease.notAcquired(
                "maintenance:workflow-wakeup-dead-letter-audit-retention",
                "owner-a");
        WorkflowRunWakeupDeadLetterAuditRetentionService service = service(sink);
        service.meterRegistry = meterRegistry;
        service.leaseRepository = leaseRepository;
        service.leaseOwnerId = "owner-a";
        service.enabled = true;
        service.olderThan = Optional.of(Duration.ofDays(1));

        service.runConfiguredRetention(false, null).await().indefinitely();

        assertEquals(1.0, counter(
                meterRegistry,
                "gamelan.workflow.wakeup.dead_letter.audit.retention.runs",
                "outcome", "skipped",
                "reason", "retention_lease_unavailable",
                "dry_run", "true",
                "lease", "unavailable"));
    }

    @Test
    void metricsRecordFailureOnce() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        RecordingAuditSink sink = new RecordingAuditSink();
        sink.purgeUni = Uni.createFrom().failure(new IllegalStateException("database unavailable"));
        WorkflowRunWakeupDeadLetterAuditRetentionService service = service(sink);
        service.meterRegistry = meterRegistry;
        service.enabled = true;
        service.olderThan = Optional.of(Duration.ofDays(1));

        assertThrows(IllegalStateException.class,
                () -> service.runConfiguredRetention(false, null).await().indefinitely());

        assertEquals(1.0, counter(
                meterRegistry,
                "gamelan.workflow.wakeup.dead_letter.audit.retention.runs",
                "outcome", "failed",
                "reason", "failure",
                "dry_run", "true",
                "lease", "not_required"));
        assertEquals(1, timerCount(
                meterRegistry,
                "gamelan.workflow.wakeup.dead_letter.audit.retention.duration",
                "outcome", "failed",
                "reason", "failure",
                "dry_run", "true",
                "lease", "not_required"));
    }

    @Test
    void concurrentRetentionRunIsSkippedWhileFirstRunIsActive() {
        RecordingAuditSink sink = new RecordingAuditSink();
        CompletableFuture<AuditPurgeResult> pendingPurge = new CompletableFuture<>();
        sink.purgeUni = Uni.createFrom().completionStage(pendingPurge);
        WorkflowRunWakeupDeadLetterAuditRetentionService service = service(sink);
        service.enabled = true;
        service.olderThan = Optional.of(Duration.ofDays(1));

        Uni<AuditRetentionRunResult> first = service.runConfiguredRetention(false, null);

        AuditRetentionStatus runningStatus = service.status(null);
        assertEquals(true, runningStatus.running());
        assertNull(runningStatus.lastResult());

        AuditRetentionRunResult second = service.runConfiguredRetention(false, null).await().indefinitely();

        assertEquals(false, second.executed());
        assertEquals("retention_already_running", second.skippedReason());
        assertEquals(true, service.status(null).running());
        assertEquals(second, service.status(null).lastResult());

        pendingPurge.complete(new AuditPurgeResult(1, 1, true, List.of("audit-1")));
        AuditRetentionRunResult firstResult = first.await().indefinitely();

        assertEquals(true, firstResult.executed());
        assertEquals(false, service.status(null).running());
        assertEquals(firstResult, service.status(null).lastResult());
    }

    @Test
    void failedRetentionRunIsTrackedAndReleasesSingleFlightGuard() {
        RecordingAuditSink sink = new RecordingAuditSink();
        sink.purgeUni = Uni.createFrom().failure(new IllegalStateException("database unavailable"));
        WorkflowRunWakeupDeadLetterAuditRetentionService service = service(sink);
        service.enabled = true;
        service.olderThan = Optional.of(Duration.ofDays(1));

        assertThrows(IllegalStateException.class,
                () -> service.runConfiguredRetention(false, null).await().indefinitely());

        assertEquals(false, service.status(null).running());
        assertEquals("database unavailable", service.status(null).lastResult().error());
        sink.purgeUni = null;
        assertEquals(true, service.runConfiguredRetention(false, null).await().indefinitely().executed());
    }

    @Test
    void invalidConfiguredPolicyIsRejected() {
        WorkflowRunWakeupDeadLetterAuditRetentionService service = service(new RecordingAuditSink());
        service.retainLatest = -2;

        assertThrows(IllegalArgumentException.class, () -> service.configuredPolicy(null));
    }

    private static double counter(SimpleMeterRegistry meterRegistry, String name, String... tags) {
        var counter = meterRegistry.find(name).tags(tags).counter();
        return counter != null ? counter.count() : 0.0;
    }

    private static long timerCount(SimpleMeterRegistry meterRegistry, String name, String... tags) {
        var timer = meterRegistry.find(name).tags(tags).timer();
        return timer != null ? timer.count() : 0L;
    }

    private static WorkflowRunWakeupDeadLetterAuditRetentionService service(RecordingAuditSink sink) {
        WorkflowRunWakeupDeadLetterAuditRetentionService service =
                new WorkflowRunWakeupDeadLetterAuditRetentionService();
        service.auditSink = sink;
        service.enabled = false;
        service.olderThan = Optional.empty();
        service.retainLatest = -1;
        service.dryRun = true;
        service.queryLimit = 1000;
        service.operation = Optional.empty();
        service.outcome = Optional.empty();
        service.intentId = Optional.empty();
        service.runId = Optional.empty();
        service.tenantId = Optional.empty();
        service.dryRunFilter = Optional.empty();
        service.leaseOwnerId = "";
        service.leaseTtl = Duration.ofMinutes(5);
        return service;
    }

    private static final class RecordingAuditSink implements WorkflowRunWakeupDeadLetterAuditSink {
        private AuditPurgePolicy purgePolicy;
        private AuditPurgeResult purgeResult = AuditPurgeResult.empty(true);
        private Uni<AuditPurgeResult> purgeUni;

        @Override
        public Uni<Void> append(WorkflowRunWakeupDeadLetterAuditEvent event) {
            return Uni.createFrom().voidItem();
        }

        @Override
        public Uni<AuditPurgeResult> purge(AuditPurgePolicy policy) {
            purgePolicy = policy;
            return purgeUni != null ? purgeUni : Uni.createFrom().item(purgeResult);
        }
    }

    private static final class RecordingLeaseRepository implements WorkflowRecoveryLeaseRepository {
        private WorkflowRecoveryLease nextAcquire;
        private WorkflowRecoveryLease releasedLease;

        @Override
        public Uni<WorkflowRecoveryLease> tryAcquireRecoveryLease(
                String leaseName,
                String ownerId,
                Duration ttl,
                Instant now) {
            WorkflowRecoveryLease lease = nextAcquire != null
                    ? nextAcquire
                    : WorkflowRecoveryLease.acquired(leaseName, ownerId, now, now.plus(ttl));
            return Uni.createFrom().item(lease);
        }

        @Override
        public Uni<Void> releaseRecoveryLease(WorkflowRecoveryLease lease) {
            releasedLease = lease;
            return Uni.createFrom().voidItem();
        }
    }
}
