package tech.kayys.gamelan.runtime.resource;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;

import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
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

@Path("/api/v1/workflow-wakeup-dead-letter-audit")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class WorkflowRunWakeupDeadLetterAuditResource {

    @Inject
    WorkflowRunWakeupDeadLetterAuditSink auditSink;

    @Inject
    WorkflowRunWakeupDeadLetterAuditRetentionService retentionService;

    @Inject
    WorkflowRunWakeupDeadLetterAuditRetentionScheduler retentionScheduler;

    @GET
    public Uni<List<WorkflowRunWakeupDeadLetterAuditEvent>> list(
            @QueryParam("limit") Integer limit,
            @QueryParam("operation") String operation,
            @QueryParam("outcome") String outcome,
            @QueryParam("intentId") String intentId,
            @QueryParam("runId") String runId,
            @QueryParam("tenantId") String tenantId,
            @QueryParam("dryRun") Boolean dryRun,
            @QueryParam("occurredFrom") String occurredFrom,
            @QueryParam("occurredTo") String occurredTo) {
        return auditSink.entries(query(limit, operation, outcome, intentId, runId, tenantId, dryRun, occurredFrom, occurredTo));
    }

    @GET
    @Path("/count")
    public Uni<Long> count(
            @QueryParam("operation") String operation,
            @QueryParam("outcome") String outcome,
            @QueryParam("intentId") String intentId,
            @QueryParam("runId") String runId,
            @QueryParam("tenantId") String tenantId,
            @QueryParam("dryRun") Boolean dryRun,
            @QueryParam("occurredFrom") String occurredFrom,
            @QueryParam("occurredTo") String occurredTo) {
        return auditSink.count(query(null, operation, outcome, intentId, runId, tenantId, dryRun, occurredFrom, occurredTo));
    }

    @GET
    @Path("/summary")
    public Uni<AuditSummary> summary(
            @QueryParam("operation") String operation,
            @QueryParam("outcome") String outcome,
            @QueryParam("intentId") String intentId,
            @QueryParam("runId") String runId,
            @QueryParam("tenantId") String tenantId,
            @QueryParam("dryRun") Boolean dryRun,
            @QueryParam("occurredFrom") String occurredFrom,
            @QueryParam("occurredTo") String occurredTo) {
        return auditSink.summary(query(null, operation, outcome, intentId, runId, tenantId, dryRun, occurredFrom, occurredTo));
    }

    @POST
    @Path("/purge")
    public Uni<AuditPurgeResult> purge(
            @QueryParam("operation") String operation,
            @QueryParam("outcome") String outcome,
            @QueryParam("intentId") String intentId,
            @QueryParam("runId") String runId,
            @QueryParam("tenantId") String tenantId,
            @QueryParam("dryRunFilter") Boolean dryRunFilter,
            @QueryParam("occurredFrom") String occurredFrom,
            @QueryParam("occurredTo") String occurredTo,
            @QueryParam("olderThanSeconds") Long olderThanSeconds,
            @QueryParam("retainLatest") Integer retainLatest,
            @QueryParam("dryRun") Boolean dryRun,
            @QueryParam("all") Boolean all) {
        AuditQuery query = query(null, operation, outcome, intentId, runId, tenantId, dryRunFilter, occurredFrom, occurredTo);
        if (!hasFilters(query) && !Boolean.TRUE.equals(all)) {
            return Uni.createFrom().failure(new BadRequestException(
                    "Workflow wake-up dead-letter audit purge requires a filter or all=true"));
        }
        AuditPurgePolicy policy = new AuditPurgePolicy(
                query,
                olderThan(olderThanSeconds),
                retainLatest(retainLatest),
                !Boolean.FALSE.equals(dryRun));
        if (!policy.hasRetentionCriteria()) {
            return Uni.createFrom().failure(new BadRequestException(
                    "Workflow wake-up dead-letter audit purge requires olderThanSeconds or retainLatest"));
        }
        return auditSink.purge(policy);
    }

    @GET
    @Path("/retention/policy")
    public AuditRetentionPolicy retentionPolicy(@QueryParam("dryRun") Boolean dryRun) {
        return retentionService.configuredPolicy(dryRun);
    }

    @GET
    @Path("/retention/status")
    public AuditRetentionStatus retentionStatus(@QueryParam("dryRun") Boolean dryRun) {
        return retentionService.status(dryRun);
    }

    @POST
    @Path("/retention/run")
    public Uni<AuditRetentionRunResult> runRetention(
            @QueryParam("force") Boolean force,
            @QueryParam("dryRun") Boolean dryRun) {
        return retentionService.runConfiguredRetention(Boolean.TRUE.equals(force), dryRun);
    }

    @GET
    @Path("/retention/schedule/status")
    public ScheduledAuditRetentionStatus retentionScheduleStatus() {
        return retentionScheduler.status();
    }

    private static AuditQuery query(
            Integer limit,
            String operation,
            String outcome,
            String intentId,
            String runId,
            String tenantId,
            Boolean dryRun,
            String occurredFrom,
            String occurredTo) {
        try {
            return new AuditQuery(
                    limit != null ? limit : 100,
                    operation(operation),
                    outcome(outcome),
                    intentId,
                    runId,
                    tenantId,
                    dryRun,
                    instant(occurredFrom, "occurredFrom"),
                    instant(occurredTo, "occurredTo"));
        } catch (IllegalArgumentException error) {
            throw new BadRequestException(error.getMessage(), error);
        }
    }

    private static Operation operation(String value) {
        return value != null && !value.isBlank()
                ? Operation.valueOf(value.trim().toUpperCase())
                : null;
    }

    private static Outcome outcome(String value) {
        return value != null && !value.isBlank()
                ? Outcome.valueOf(value.trim().toUpperCase())
                : null;
    }

    private static Instant instant(String value, String name) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value.trim());
        } catch (DateTimeParseException error) {
            throw new BadRequestException(name + " must be an ISO-8601 instant", error);
        }
    }

    private static Duration olderThan(Long olderThanSeconds) {
        if (olderThanSeconds == null) {
            return null;
        }
        if (olderThanSeconds < 0) {
            throw new BadRequestException("olderThanSeconds cannot be negative");
        }
        return Duration.ofSeconds(olderThanSeconds);
    }

    private static int retainLatest(Integer retainLatest) {
        if (retainLatest == null) {
            return -1;
        }
        if (retainLatest < 0) {
            throw new BadRequestException("retainLatest cannot be negative");
        }
        return retainLatest;
    }

    private static boolean hasFilters(AuditQuery query) {
        return query.operation() != null
                || query.outcome() != null
                || query.intentId() != null
                || query.runId() != null
                || query.tenantId() != null
                || query.dryRun() != null
                || query.occurredFrom() != null
                || query.occurredTo() != null;
    }
}
