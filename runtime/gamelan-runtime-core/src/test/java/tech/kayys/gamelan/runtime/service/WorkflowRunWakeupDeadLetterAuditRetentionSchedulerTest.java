package tech.kayys.gamelan.runtime.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupDeadLetterAuditSink.AuditPurgeResult;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupDeadLetterAuditSink.AuditQuery;
import tech.kayys.gamelan.runtime.service.WorkflowRunWakeupDeadLetterAuditRetentionService.AuditRetentionPolicy;
import tech.kayys.gamelan.runtime.service.WorkflowRunWakeupDeadLetterAuditRetentionService.AuditRetentionRunResult;

class WorkflowRunWakeupDeadLetterAuditRetentionSchedulerTest {

    @Test
    void disabledScheduleDoesNotInvokeRetentionService() {
        RecordingRetentionService retentionService = new RecordingRetentionService();
        WorkflowRunWakeupDeadLetterAuditRetentionScheduler scheduler = scheduler(retentionService);
        scheduler.scheduleEnabled = false;

        scheduler.runScheduledRetention();

        assertEquals(0, retentionService.calls);
        assertEquals(false, scheduler.status().enabled());
        assertEquals(false, scheduler.status().running());
        assertNull(scheduler.status().lastStartedAt());
    }

    @Test
    void enabledScheduleDelegatesToConfiguredRetention() {
        RecordingRetentionService retentionService = new RecordingRetentionService();
        WorkflowRunWakeupDeadLetterAuditRetentionScheduler scheduler = scheduler(retentionService);
        scheduler.scheduleEnabled = true;
        scheduler.scheduleDryRun = Optional.of(false);

        scheduler.runScheduledRetention();

        assertEquals(1, retentionService.calls);
        assertEquals(false, retentionService.dryRunOverride);
        assertEquals(false, retentionService.force);
        assertEquals(retentionService.result, scheduler.status().lastResult());
        assertEquals(false, scheduler.status().running());
        assertNull(scheduler.status().lastError());
    }

    @Test
    void scheduleSkipsOverlappingTriggerWhileRunIsActive() {
        RecordingRetentionService retentionService = new RecordingRetentionService();
        CompletableFuture<AuditRetentionRunResult> pending = new CompletableFuture<>();
        retentionService.next = Uni.createFrom().completionStage(pending);
        WorkflowRunWakeupDeadLetterAuditRetentionScheduler scheduler = scheduler(retentionService);
        scheduler.scheduleEnabled = true;

        scheduler.runScheduledRetention();
        scheduler.runScheduledRetention();

        assertEquals(1, retentionService.calls);
        assertEquals(true, scheduler.status().running());
        assertEquals("schedule_already_running", scheduler.status().lastSkippedReason());

        pending.complete(retentionService.result);

        assertEquals(false, scheduler.status().running());
        assertEquals(retentionService.result, scheduler.status().lastResult());
    }

    @Test
    void scheduleRecordsRetentionFailure() {
        RecordingRetentionService retentionService = new RecordingRetentionService();
        retentionService.next = Uni.createFrom().failure(new IllegalStateException("retention failed"));
        WorkflowRunWakeupDeadLetterAuditRetentionScheduler scheduler = scheduler(retentionService);
        scheduler.scheduleEnabled = true;

        scheduler.runScheduledRetention();

        assertEquals(1, retentionService.calls);
        assertEquals(false, scheduler.status().running());
        assertEquals("retention failed", scheduler.status().lastError());
    }

    @Test
    void scheduleRecordsMissingRetentionServiceAsSkipped() {
        WorkflowRunWakeupDeadLetterAuditRetentionScheduler scheduler = scheduler(null);
        scheduler.scheduleEnabled = true;

        scheduler.runScheduledRetention();

        assertEquals("retention_service_unavailable", scheduler.status().lastSkippedReason());
        assertEquals(false, scheduler.status().running());
    }

    private static WorkflowRunWakeupDeadLetterAuditRetentionScheduler scheduler(
            WorkflowRunWakeupDeadLetterAuditRetentionService retentionService) {
        WorkflowRunWakeupDeadLetterAuditRetentionScheduler scheduler =
                new WorkflowRunWakeupDeadLetterAuditRetentionScheduler();
        scheduler.retentionService = retentionService;
        scheduler.scheduleEnabled = false;
        scheduler.scheduleDryRun = Optional.empty();
        return scheduler;
    }

    private static final class RecordingRetentionService extends WorkflowRunWakeupDeadLetterAuditRetentionService {
        private final AuditRetentionPolicy policy = new AuditRetentionPolicy(
                true,
                AuditQuery.all(),
                Duration.ofDays(1),
                -1,
                true);
        private final AuditRetentionRunResult result = AuditRetentionRunResult.executed(
                policy,
                new AuditPurgeResult(2, 1, true, List.of("audit-1", "audit-2")),
                Instant.parse("2026-06-08T00:00:00Z"));

        private Uni<AuditRetentionRunResult> next = Uni.createFrom().item(result);
        private int calls;
        private boolean force;
        private Boolean dryRunOverride;

        @Override
        public Uni<AuditRetentionRunResult> runConfiguredRetention(boolean force, Boolean dryRunOverride) {
            calls++;
            this.force = force;
            this.dryRunOverride = dryRunOverride;
            return next;
        }
    }
}
