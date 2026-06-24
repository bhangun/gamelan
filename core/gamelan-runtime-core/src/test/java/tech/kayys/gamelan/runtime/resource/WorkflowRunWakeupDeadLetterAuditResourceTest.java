package tech.kayys.gamelan.runtime.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.BadRequestException;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupDeadLetterAuditEvent;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupDeadLetterAuditEvent.Operation;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupDeadLetterAuditEvent.Outcome;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupDeadLetterAuditSink;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupDeadLetterAuditSink.AuditPurgePolicy;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupDeadLetterAuditSink.AuditPurgeResult;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupDeadLetterAuditSink.AuditQuery;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupDeadLetterAuditSink.AuditSummary;
import tech.kayys.gamelan.runtime.service.WorkflowRunWakeupDeadLetterAuditRetentionService;
import tech.kayys.gamelan.runtime.service.WorkflowRunWakeupDeadLetterAuditRetentionService.AuditRetentionPolicy;
import tech.kayys.gamelan.runtime.service.WorkflowRunWakeupDeadLetterAuditRetentionService.AuditRetentionRunResult;
import tech.kayys.gamelan.runtime.service.WorkflowRunWakeupDeadLetterAuditRetentionService.AuditRetentionStatus;
import tech.kayys.gamelan.runtime.service.WorkflowRunWakeupDeadLetterAuditRetentionScheduler;
import tech.kayys.gamelan.runtime.service.WorkflowRunWakeupDeadLetterAuditRetentionScheduler.ScheduledAuditRetentionStatus;

class WorkflowRunWakeupDeadLetterAuditResourceTest {

    @Test
    void listPassesFiltersToAuditSink() {
        RecordingAuditSink sink = new RecordingAuditSink();
        WorkflowRunWakeupDeadLetterAuditEvent event = WorkflowRunWakeupDeadLetterAuditEvent.single(
                Operation.DELETE,
                "intent-1",
                Outcome.SUCCEEDED,
                null);
        sink.entries = List.of(event);
        WorkflowRunWakeupDeadLetterAuditResource resource = resource(sink);

        List<WorkflowRunWakeupDeadLetterAuditEvent> listed = resource
                .list(
                        25,
                        " bulk_replay ",
                        " succeeded ",
                        " intent-1 ",
                        " run-1 ",
                        " tenant-1 ",
                        false,
                        "2026-06-07T00:00:00Z",
                        "2026-06-09T00:00:00Z")
                .await()
                .indefinitely();

        assertEquals(List.of(event), listed);
        assertEquals(25, sink.entriesQuery.limit());
        assertEquals(Operation.BULK_REPLAY, sink.entriesQuery.operation());
        assertEquals(Outcome.SUCCEEDED, sink.entriesQuery.outcome());
        assertEquals("intent-1", sink.entriesQuery.intentId());
        assertEquals("run-1", sink.entriesQuery.runId());
        assertEquals("tenant-1", sink.entriesQuery.tenantId());
        assertEquals(false, sink.entriesQuery.dryRun());
        assertEquals(Instant.parse("2026-06-07T00:00:00Z"), sink.entriesQuery.occurredFrom());
        assertEquals(Instant.parse("2026-06-09T00:00:00Z"), sink.entriesQuery.occurredTo());
    }

    @Test
    void countPassesFiltersToAuditSink() {
        RecordingAuditSink sink = new RecordingAuditSink();
        sink.count = 7L;
        WorkflowRunWakeupDeadLetterAuditResource resource = resource(sink);

        long count = resource.count("purge", null, null, "run-1", null, true, null, null)
                .await()
                .indefinitely();

        assertEquals(7L, count);
        assertEquals(100, sink.countQuery.limit());
        assertEquals(Operation.PURGE, sink.countQuery.operation());
        assertEquals("run-1", sink.countQuery.runId());
        assertEquals(true, sink.countQuery.dryRun());
    }

    @Test
    void summaryPassesFiltersToAuditSink() {
        RecordingAuditSink sink = new RecordingAuditSink();
        sink.summary = new AuditSummary(3, 4, 2, 1, 1, List.of());
        WorkflowRunWakeupDeadLetterAuditResource resource = resource(sink);

        AuditSummary summary = resource.summary(null, "failed", null, "run-1", null, false, null, null)
                .await()
                .indefinitely();

        assertEquals(sink.summary, summary);
        assertEquals(100, sink.summaryQuery.limit());
        assertEquals(Outcome.FAILED, sink.summaryQuery.outcome());
        assertEquals("run-1", sink.summaryQuery.runId());
        assertEquals(false, sink.summaryQuery.dryRun());
    }

    @Test
    void purgeRequiresFilterOrExplicitAllFlag() {
        WorkflowRunWakeupDeadLetterAuditResource resource = resource(new RecordingAuditSink());

        assertThrows(BadRequestException.class,
                () -> resource.purge(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        3600L,
                        null,
                        true,
                        false)
                        .await()
                        .indefinitely());
    }

    @Test
    void purgeRequiresRetentionCriteria() {
        WorkflowRunWakeupDeadLetterAuditResource resource = resource(new RecordingAuditSink());

        assertThrows(BadRequestException.class,
                () -> resource.purge(
                        null,
                        null,
                        null,
                        "run-1",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        true,
                        false)
                        .await()
                        .indefinitely());
    }

    @Test
    void purgeDefaultsToDryRunAndPassesRetentionPolicy() {
        RecordingAuditSink sink = new RecordingAuditSink();
        sink.purgeResult = new AuditPurgeResult(2, 0, true, List.of("audit-1", "audit-2"));
        WorkflowRunWakeupDeadLetterAuditResource resource = resource(sink);

        AuditPurgeResult result = resource.purge(
                " purge ",
                " dry_run ",
                " intent-1 ",
                " run-1 ",
                " tenant-1 ",
                true,
                "2026-06-07T00:00:00Z",
                "2026-06-09T00:00:00Z",
                3600L,
                10,
                null,
                false)
                .await()
                .indefinitely();

        assertEquals(sink.purgeResult, result);
        assertEquals(Operation.PURGE, sink.purgePolicy.query().operation());
        assertEquals(Outcome.DRY_RUN, sink.purgePolicy.query().outcome());
        assertEquals("intent-1", sink.purgePolicy.query().intentId());
        assertEquals("run-1", sink.purgePolicy.query().runId());
        assertEquals("tenant-1", sink.purgePolicy.query().tenantId());
        assertEquals(true, sink.purgePolicy.query().dryRun());
        assertEquals(Instant.parse("2026-06-07T00:00:00Z"), sink.purgePolicy.query().occurredFrom());
        assertEquals(Instant.parse("2026-06-09T00:00:00Z"), sink.purgePolicy.query().occurredTo());
        assertEquals(Duration.ofSeconds(3600), sink.purgePolicy.olderThan());
        assertEquals(10, sink.purgePolicy.retainLatest());
        assertEquals(true, sink.purgePolicy.dryRun());
    }

    @Test
    void purgeAllowsActualDeleteWhenDryRunIsFalseAndAllIsExplicit() {
        RecordingAuditSink sink = new RecordingAuditSink();
        sink.purgeResult = new AuditPurgeResult(1, 1, false, List.of("audit-1"));
        WorkflowRunWakeupDeadLetterAuditResource resource = resource(sink);

        AuditPurgeResult result = resource.purge(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                0,
                false,
                true)
                .await()
                .indefinitely();

        assertEquals(sink.purgeResult, result);
        assertEquals(null, sink.purgePolicy.query().operation());
        assertEquals(null, sink.purgePolicy.query().runId());
        assertEquals(null, sink.purgePolicy.olderThan());
        assertEquals(0, sink.purgePolicy.retainLatest());
        assertEquals(false, sink.purgePolicy.dryRun());
    }

    @Test
    void purgeRejectsNegativeRetentionInputs() {
        WorkflowRunWakeupDeadLetterAuditResource resource = resource(new RecordingAuditSink());

        assertThrows(BadRequestException.class,
                () -> resource.purge(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        -1L,
                        null,
                        true,
                        true));
        assertThrows(BadRequestException.class,
                () -> resource.purge(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        -1,
                        true,
                        true));
    }

    @Test
    void retentionPolicyPassesDryRunOverrideToService() {
        RecordingRetentionService retentionService = new RecordingRetentionService();
        WorkflowRunWakeupDeadLetterAuditResource resource = resource(new RecordingAuditSink(), retentionService);

        AuditRetentionPolicy policy = resource.retentionPolicy(false);

        assertEquals(retentionService.policy, policy);
        assertEquals(false, retentionService.policyDryRunOverride);
    }

    @Test
    void retentionStatusPassesDryRunOverrideToService() {
        RecordingRetentionService retentionService = new RecordingRetentionService();
        WorkflowRunWakeupDeadLetterAuditResource resource = resource(new RecordingAuditSink(), retentionService);

        AuditRetentionStatus status = resource.retentionStatus(false);

        assertEquals(retentionService.status, status);
        assertEquals(false, retentionService.statusDryRunOverride);
    }

    @Test
    void runRetentionPassesForceAndDryRunOverrideToService() {
        RecordingRetentionService retentionService = new RecordingRetentionService();
        WorkflowRunWakeupDeadLetterAuditResource resource = resource(new RecordingAuditSink(), retentionService);

        AuditRetentionRunResult result = resource.runRetention(true, false).await().indefinitely();

        assertEquals(retentionService.result, result);
        assertEquals(true, retentionService.runForce);
        assertEquals(false, retentionService.runDryRunOverride);
    }

    @Test
    void retentionScheduleStatusPassesThroughSchedulerStatus() {
        RecordingRetentionScheduler retentionScheduler = new RecordingRetentionScheduler();
        WorkflowRunWakeupDeadLetterAuditResource resource = resource(
                new RecordingAuditSink(),
                new RecordingRetentionService(),
                retentionScheduler);

        ScheduledAuditRetentionStatus status = resource.retentionScheduleStatus();

        assertEquals(retentionScheduler.status, status);
        assertEquals(1, retentionScheduler.statusCalls);
    }

    @Test
    void rejectsInvalidQueryValues() {
        WorkflowRunWakeupDeadLetterAuditResource resource = resource(new RecordingAuditSink());

        assertThrows(BadRequestException.class,
                () -> resource.list(null, "unknown", null, null, null, null, null, null, null));
        assertThrows(BadRequestException.class,
                () -> resource.list(null, null, "unknown", null, null, null, null, null, null));
        assertThrows(BadRequestException.class,
                () -> resource.list(null, null, null, null, null, null, null, "not-an-instant", null));
        assertThrows(BadRequestException.class,
                () -> resource.list(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        "2026-06-09T00:00:00Z",
                        "2026-06-07T00:00:00Z"));
    }

    private static WorkflowRunWakeupDeadLetterAuditResource resource(RecordingAuditSink sink) {
        return resource(sink, null);
    }

    private static WorkflowRunWakeupDeadLetterAuditResource resource(
            RecordingAuditSink sink,
            WorkflowRunWakeupDeadLetterAuditRetentionService retentionService) {
        return resource(sink, retentionService, null);
    }

    private static WorkflowRunWakeupDeadLetterAuditResource resource(
            RecordingAuditSink sink,
            WorkflowRunWakeupDeadLetterAuditRetentionService retentionService,
            WorkflowRunWakeupDeadLetterAuditRetentionScheduler retentionScheduler) {
        WorkflowRunWakeupDeadLetterAuditResource resource = new WorkflowRunWakeupDeadLetterAuditResource();
        resource.auditSink = sink;
        resource.retentionService = retentionService;
        resource.retentionScheduler = retentionScheduler;
        return resource;
    }

    private static final class RecordingAuditSink implements WorkflowRunWakeupDeadLetterAuditSink {
        private List<WorkflowRunWakeupDeadLetterAuditEvent> entries = List.of();
        private long count;
        private AuditSummary summary = AuditSummary.empty();
        private AuditPurgeResult purgeResult = AuditPurgeResult.empty(true);
        private AuditQuery entriesQuery;
        private AuditQuery countQuery;
        private AuditQuery summaryQuery;
        private AuditPurgePolicy purgePolicy;

        @Override
        public Uni<Void> append(WorkflowRunWakeupDeadLetterAuditEvent event) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Uni<List<WorkflowRunWakeupDeadLetterAuditEvent>> entries(AuditQuery query) {
            entriesQuery = query;
            return Uni.createFrom().item(entries);
        }

        @Override
        public Uni<Long> count(AuditQuery query) {
            countQuery = query;
            return Uni.createFrom().item(count);
        }

        @Override
        public Uni<AuditSummary> summary(AuditQuery query) {
            summaryQuery = query;
            return Uni.createFrom().item(summary);
        }

        @Override
        public Uni<AuditPurgeResult> purge(AuditPurgePolicy policy) {
            purgePolicy = policy;
            return Uni.createFrom().item(purgeResult);
        }
    }

    private static final class RecordingRetentionService extends WorkflowRunWakeupDeadLetterAuditRetentionService {
        private final AuditRetentionPolicy policy = new AuditRetentionPolicy(
                true,
                AuditQuery.all(),
                Duration.ofDays(7),
                100,
                true);
        private final AuditRetentionRunResult result = AuditRetentionRunResult.executed(
                policy,
                new AuditPurgeResult(1, 1, false, List.of("audit-1")),
                Instant.parse("2026-06-08T00:00:00Z"));
        private final AuditRetentionStatus status = new AuditRetentionStatus(
                false,
                Instant.parse("2026-06-08T00:00:01Z"),
                policy,
                result,
                null);
        private Boolean policyDryRunOverride;
        private Boolean statusDryRunOverride;
        private Boolean runForce;
        private Boolean runDryRunOverride;

        @Override
        public AuditRetentionPolicy configuredPolicy(Boolean dryRunOverride) {
            policyDryRunOverride = dryRunOverride;
            return policy;
        }

        @Override
        public AuditRetentionStatus status(Boolean dryRunOverride) {
            statusDryRunOverride = dryRunOverride;
            return status;
        }

        @Override
        public Uni<AuditRetentionRunResult> runConfiguredRetention(boolean force, Boolean dryRunOverride) {
            runForce = force;
            runDryRunOverride = dryRunOverride;
            return Uni.createFrom().item(result);
        }
    }

    private static final class RecordingRetentionScheduler
            extends WorkflowRunWakeupDeadLetterAuditRetentionScheduler {
        private final ScheduledAuditRetentionStatus status = new ScheduledAuditRetentionStatus(
                true,
                false,
                Instant.parse("2026-06-08T00:00:02Z"),
                false,
                Instant.parse("2026-06-08T00:00:00Z"),
                Instant.parse("2026-06-08T00:00:01Z"),
                null,
                null,
                null);
        private int statusCalls;

        @Override
        public ScheduledAuditRetentionStatus status() {
            statusCalls++;
            return status;
        }
    }
}
