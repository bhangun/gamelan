package tech.kayys.gamelan.runtime.service;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.quarkus.scheduler.Scheduled;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import tech.kayys.gamelan.runtime.service.WorkflowRunWakeupDeadLetterAuditRetentionService.AuditRetentionRunResult;

@ApplicationScoped
public class WorkflowRunWakeupDeadLetterAuditRetentionScheduler {

    private static final Logger LOG =
            LoggerFactory.getLogger(WorkflowRunWakeupDeadLetterAuditRetentionScheduler.class);

    private final AtomicBoolean scheduledRunActive = new AtomicBoolean();
    private final AtomicReference<Instant> lastStartedAt = new AtomicReference<>();
    private final AtomicReference<Instant> lastCompletedAt = new AtomicReference<>();
    private final AtomicReference<String> lastSkippedReason = new AtomicReference<>();
    private final AtomicReference<String> lastError = new AtomicReference<>();
    private final AtomicReference<AuditRetentionRunResult> lastResult = new AtomicReference<>();

    @Inject
    WorkflowRunWakeupDeadLetterAuditRetentionService retentionService;

    @ConfigProperty(
            name = "gamelan.workflow.wakeup.dead-letter-audit.retention.schedule.enabled",
            defaultValue = "false")
    boolean scheduleEnabled;

    @ConfigProperty(name = "gamelan.workflow.wakeup.dead-letter-audit.retention.schedule.dry-run")
    Optional<Boolean> scheduleDryRun = Optional.empty();

    @Scheduled(every = "{gamelan.workflow.wakeup.dead-letter-audit.retention.schedule.interval:5m}")
    void runScheduledRetention() {
        if (!scheduleEnabled) {
            return;
        }
        if (retentionService == null) {
            recordSkipped("retention_service_unavailable");
            return;
        }
        if (!scheduledRunActive.compareAndSet(false, true)) {
            recordSkipped("schedule_already_running");
            return;
        }

        lastStartedAt.set(Instant.now());
        lastSkippedReason.set(null);
        lastError.set(null);
        try {
            retentionService.runConfiguredRetention(false, scheduleDryRun.orElse(null))
                    .eventually(() -> {
                        scheduledRunActive.set(false);
                        lastCompletedAt.set(Instant.now());
                        return Uni.createFrom().voidItem();
                    })
                    .subscribe().with(
                            this::recordResult,
                            this::recordFailure);
        } catch (RuntimeException error) {
            scheduledRunActive.set(false);
            lastCompletedAt.set(Instant.now());
            recordFailure(error);
        }
    }

    public ScheduledAuditRetentionStatus status() {
        return new ScheduledAuditRetentionStatus(
                scheduleEnabled,
                scheduledRunActive.get(),
                Instant.now(),
                scheduleDryRun.orElse(null),
                lastStartedAt.get(),
                lastCompletedAt.get(),
                lastSkippedReason.get(),
                lastError.get(),
                lastResult.get());
    }

    private void recordResult(AuditRetentionRunResult result) {
        lastResult.set(result);
        lastError.set(null);
        if (result != null && !result.executed() && result.skippedReason() != null) {
            lastSkippedReason.set(result.skippedReason());
        }
    }

    private void recordSkipped(String reason) {
        lastSkippedReason.set(reason);
        lastError.set(null);
    }

    private void recordFailure(Throwable error) {
        String message = errorMessage(error);
        lastError.set(message);
        LOG.warn("Scheduled workflow wake-up dead-letter audit retention failed: {}", message);
        LOG.debug("Scheduled audit retention failure details", error);
    }

    private static String errorMessage(Throwable error) {
        if (error == null) {
            return null;
        }
        return error.getMessage() != null ? error.getMessage() : error.getClass().getName();
    }

    public record ScheduledAuditRetentionStatus(
            boolean enabled,
            boolean running,
            Instant observedAt,
            Boolean dryRunOverride,
            Instant lastStartedAt,
            Instant lastCompletedAt,
            String lastSkippedReason,
            String lastError,
            AuditRetentionRunResult lastResult) {
    }
}
