package tech.kayys.gamelan.runtime.resource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupDeadLetter;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupDeadLetterAuditEvent;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupDeadLetterAuditEvent.Operation;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupDeadLetterAuditEvent.Outcome;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupDeadLetterAuditSink;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupIntent;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupOutbox;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupOutbox.DeadLetterPurgePolicy;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupOutbox.DeadLetterPurgeResult;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupOutbox.DeadLetterQuery;

@Path("/api/v1/workflow-wakeup-dead-letters")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class WorkflowRunWakeupDeadLetterResource {

    @Inject
    WorkflowRunWakeupOutbox wakeupOutbox;

    @Inject
    WorkflowRunWakeupDeadLetterAuditSink auditSink;

    @GET
    public Uni<List<WorkflowRunWakeupDeadLetter>> list(
            @QueryParam("limit") Integer limit,
            @QueryParam("runId") String runId,
            @QueryParam("tenantId") String tenantId,
            @QueryParam("reason") String reason,
            @QueryParam("deadLetterReason") String deadLetterReason) {
        return wakeupOutbox.deadLetters(query(limit, runId, tenantId, reason, deadLetterReason));
    }

    @GET
    @Path("/count")
    public Uni<Long> count(
            @QueryParam("runId") String runId,
            @QueryParam("tenantId") String tenantId,
            @QueryParam("reason") String reason,
            @QueryParam("deadLetterReason") String deadLetterReason) {
        return wakeupOutbox.deadLetterCount(query(null, runId, tenantId, reason, deadLetterReason));
    }

    @POST
    @Path("/{intentId}/replay")
    public Uni<WorkflowRunWakeupIntent> replay(@PathParam("intentId") String intentId) {
        return wakeupOutbox.replayDeadLetter(intentId)
                .flatMap(replayed -> replayed
                        .map(intent -> audit(WorkflowRunWakeupDeadLetterAuditEvent.single(
                                Operation.REPLAY,
                                intentId,
                                Outcome.SUCCEEDED,
                                null)).replaceWith(intent))
                        .orElseGet(() -> Uni.createFrom().failure(new NotFoundException(
                                "Workflow wake-up dead letter not found: " + intentId))))
                .onFailure().call(error -> audit(WorkflowRunWakeupDeadLetterAuditEvent.single(
                        Operation.REPLAY,
                        intentId,
                        Outcome.FAILED,
                        errorMessage(error))));
    }

    @POST
    @Path("/replay")
    public Uni<DeadLetterReplayResponse> replayMatching(
            @QueryParam("limit") Integer limit,
            @QueryParam("runId") String runId,
            @QueryParam("tenantId") String tenantId,
            @QueryParam("reason") String reason,
            @QueryParam("deadLetterReason") String deadLetterReason,
            @QueryParam("all") Boolean all) {
        DeadLetterQuery query = query(limit, runId, tenantId, reason, deadLetterReason);
        if (!query.hasFilters() && !Boolean.TRUE.equals(all)) {
            return Uni.createFrom().failure(new BadRequestException(
                    "Bulk workflow wake-up dead-letter replay requires a filter or all=true"));
        }
        return wakeupOutbox.deadLetters(query)
                .flatMap(this::replaySelected)
                .flatMap(response -> audit(replayAudit(query, response)).replaceWith(response))
                .onFailure().call(error -> audit(WorkflowRunWakeupDeadLetterAuditEvent.bulk(
                        Operation.BULK_REPLAY,
                        query,
                        0,
                        0,
                        1,
                        0,
                        false,
                        List.of(),
                        errorMessage(error))));
    }

    @POST
    @Path("/purge")
    public Uni<DeadLetterPurgeResult> purge(
            @QueryParam("runId") String runId,
            @QueryParam("tenantId") String tenantId,
            @QueryParam("reason") String reason,
            @QueryParam("deadLetterReason") String deadLetterReason,
            @QueryParam("olderThanSeconds") Long olderThanSeconds,
            @QueryParam("retainLatest") Integer retainLatest,
            @QueryParam("dryRun") Boolean dryRun,
            @QueryParam("all") Boolean all) {
        DeadLetterQuery query = query(null, runId, tenantId, reason, deadLetterReason);
        if (!query.hasFilters() && !Boolean.TRUE.equals(all)) {
            return Uni.createFrom().failure(new BadRequestException(
                    "Workflow wake-up dead-letter purge requires a filter or all=true"));
        }
        DeadLetterPurgePolicy policy = new DeadLetterPurgePolicy(
                query,
                olderThan(olderThanSeconds),
                retainLatest(retainLatest),
                !Boolean.FALSE.equals(dryRun));
        if (!policy.hasRetentionCriteria()) {
            return Uni.createFrom().failure(new BadRequestException(
                    "Workflow wake-up dead-letter purge requires olderThanSeconds or retainLatest"));
        }
        return wakeupOutbox.purgeDeadLetters(policy)
                .flatMap(result -> audit(purgeAudit(policy, result)).replaceWith(result))
                .onFailure().call(error -> audit(WorkflowRunWakeupDeadLetterAuditEvent.bulk(
                        Operation.PURGE,
                        policy.query(),
                        0,
                        0,
                        1,
                        0,
                        policy.dryRun(),
                        List.of(),
                        errorMessage(error))));
    }

    @DELETE
    public Uni<DeadLetterDeleteResponse> deleteMatching(
            @QueryParam("limit") Integer limit,
            @QueryParam("runId") String runId,
            @QueryParam("tenantId") String tenantId,
            @QueryParam("reason") String reason,
            @QueryParam("deadLetterReason") String deadLetterReason,
            @QueryParam("all") Boolean all) {
        DeadLetterQuery query = query(limit, runId, tenantId, reason, deadLetterReason);
        if (!query.hasFilters() && !Boolean.TRUE.equals(all)) {
            return Uni.createFrom().failure(new BadRequestException(
                    "Bulk workflow wake-up dead-letter delete requires a filter or all=true"));
        }
        return wakeupOutbox.deadLetters(query)
                .flatMap(this::deleteSelected)
                .flatMap(response -> audit(deleteAudit(query, response)).replaceWith(response))
                .onFailure().call(error -> audit(WorkflowRunWakeupDeadLetterAuditEvent.bulk(
                        Operation.BULK_DELETE,
                        query,
                        0,
                        0,
                        1,
                        0,
                        false,
                        List.of(),
                        errorMessage(error))));
    }

    @DELETE
    @Path("/{intentId}")
    public Uni<Void> delete(@PathParam("intentId") String intentId) {
        return wakeupOutbox.deleteDeadLetter(intentId)
                .flatMap(deleted -> Boolean.TRUE.equals(deleted)
                        ? audit(WorkflowRunWakeupDeadLetterAuditEvent.single(
                                Operation.DELETE,
                                intentId,
                                Outcome.SUCCEEDED,
                                null))
                        : Uni.createFrom().failure(new NotFoundException(
                                "Workflow wake-up dead letter not found: " + intentId)))
                .onFailure().call(error -> audit(WorkflowRunWakeupDeadLetterAuditEvent.single(
                        Operation.DELETE,
                        intentId,
                        Outcome.FAILED,
                        errorMessage(error))));
    }

    private Uni<DeadLetterReplayResponse> replaySelected(List<WorkflowRunWakeupDeadLetter> deadLetters) {
        ReplayAccumulator accumulator = new ReplayAccumulator(deadLetters.size());
        Uni<ReplayAccumulator> chain = Uni.createFrom().item(accumulator);
        for (WorkflowRunWakeupDeadLetter deadLetter : deadLetters) {
            chain = chain.flatMap(current -> {
                if (current.hasFailure()) {
                    current.skip(deadLetter);
                    return Uni.createFrom().item(current);
                }
                return wakeupOutbox.replayDeadLetter(deadLetter.intentId())
                        .map(replayed -> replayed
                                .map(intent -> current.replayed(deadLetter, intent))
                                .orElseGet(() -> current.failed(
                                        deadLetter,
                                        new NotFoundException("Workflow wake-up dead letter not found: "
                                                + deadLetter.intentId()))))
                        .onFailure().recoverWithItem(error -> current.failed(deadLetter, error));
            });
        }
        return chain.map(ReplayAccumulator::response);
    }

    private Uni<DeadLetterDeleteResponse> deleteSelected(List<WorkflowRunWakeupDeadLetter> deadLetters) {
        DeleteAccumulator accumulator = new DeleteAccumulator(deadLetters.size());
        Uni<DeleteAccumulator> chain = Uni.createFrom().item(accumulator);
        for (WorkflowRunWakeupDeadLetter deadLetter : deadLetters) {
            chain = chain.flatMap(current -> {
                if (current.hasFailure()) {
                    current.skip(deadLetter);
                    return Uni.createFrom().item(current);
                }
                return wakeupOutbox.deleteDeadLetter(deadLetter.intentId())
                        .map(deleted -> Boolean.TRUE.equals(deleted)
                                ? current.deleted(deadLetter)
                                : current.failed(deadLetter, new NotFoundException(
                                        "Workflow wake-up dead letter not found: " + deadLetter.intentId())))
                        .onFailure().recoverWithItem(error -> current.failed(deadLetter, error));
            });
        }
        return chain.map(DeleteAccumulator::response);
    }

    private static DeadLetterQuery query(
            Integer limit,
            String runId,
            String tenantId,
            String reason,
            String deadLetterReason) {
        return new DeadLetterQuery(
                limit != null ? limit : 100,
                runId,
                tenantId,
                reason,
                deadLetterReason);
    }

    private Uni<Void> audit(WorkflowRunWakeupDeadLetterAuditEvent event) {
        if (auditSink == null) {
            return Uni.createFrom().voidItem();
        }
        try {
            Uni<Void> appended = auditSink.append(event);
            return appended != null
                    ? appended.onFailure().recoverWithNull()
                    : Uni.createFrom().voidItem();
        } catch (RuntimeException ignored) {
            return Uni.createFrom().voidItem();
        }
    }

    private static WorkflowRunWakeupDeadLetterAuditEvent replayAudit(
            DeadLetterQuery query,
            DeadLetterReplayResponse response) {
        return WorkflowRunWakeupDeadLetterAuditEvent.bulk(
                Operation.BULK_REPLAY,
                query,
                response.selected(),
                response.replayed(),
                response.failed(),
                response.skipped(),
                false,
                replayIntentIds(response),
                firstFailure(response.failures()));
    }

    private static WorkflowRunWakeupDeadLetterAuditEvent deleteAudit(
            DeadLetterQuery query,
            DeadLetterDeleteResponse response) {
        return WorkflowRunWakeupDeadLetterAuditEvent.bulk(
                Operation.BULK_DELETE,
                query,
                response.selected(),
                response.deleted(),
                response.failed(),
                response.skipped(),
                false,
                deleteIntentIds(response),
                firstFailure(response.failures()));
    }

    private static WorkflowRunWakeupDeadLetterAuditEvent purgeAudit(
            DeadLetterPurgePolicy policy,
            DeadLetterPurgeResult result) {
        return WorkflowRunWakeupDeadLetterAuditEvent.bulk(
                Operation.PURGE,
                policy.query(),
                result.selected(),
                result.purged(),
                0,
                0,
                result.dryRun(),
                result.intentIds(),
                null);
    }

    private static List<String> replayIntentIds(DeadLetterReplayResponse response) {
        List<String> intentIds = new ArrayList<>(response.replayedIntentIds());
        response.failures().stream()
                .map(DeadLetterReplayFailure::intentId)
                .forEach(intentIds::add);
        return intentIds;
    }

    private static List<String> deleteIntentIds(DeadLetterDeleteResponse response) {
        List<String> intentIds = new ArrayList<>(response.deletedIntentIds());
        response.failures().stream()
                .map(DeadLetterDeleteFailure::intentId)
                .forEach(intentIds::add);
        return intentIds;
    }

    private static String firstFailure(List<? extends FailureRecord> failures) {
        return failures.isEmpty() ? null : failures.getFirst().error();
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

    public record DeadLetterReplayResponse(
            int selected,
            int replayed,
            int failed,
            int skipped,
            List<String> replayedIntentIds,
            List<DeadLetterReplayFailure> failures) {
    }

    public record DeadLetterReplayFailure(
            String intentId,
            String error) implements FailureRecord {
    }

    public record DeadLetterDeleteResponse(
            int selected,
            int deleted,
            int failed,
            int skipped,
            List<String> deletedIntentIds,
            List<DeadLetterDeleteFailure> failures) {
    }

    public record DeadLetterDeleteFailure(
            String intentId,
            String error) implements FailureRecord {
    }

    private interface FailureRecord {
        String error();
    }

    private static final class ReplayAccumulator {
        private final int selected;
        private final List<String> replayedIntentIds = new ArrayList<>();
        private final List<DeadLetterReplayFailure> failures = new ArrayList<>();
        private int skipped;

        private ReplayAccumulator(int selected) {
            this.selected = selected;
        }

        private boolean hasFailure() {
            return !failures.isEmpty();
        }

        private ReplayAccumulator replayed(WorkflowRunWakeupDeadLetter deadLetter, WorkflowRunWakeupIntent ignored) {
            replayedIntentIds.add(deadLetter.intentId());
            return this;
        }

        private ReplayAccumulator failed(WorkflowRunWakeupDeadLetter deadLetter, Throwable error) {
            failures.add(new DeadLetterReplayFailure(deadLetter.intentId(), errorMessage(error)));
            return this;
        }

        private void skip(WorkflowRunWakeupDeadLetter ignored) {
            skipped++;
        }

        private DeadLetterReplayResponse response() {
            return new DeadLetterReplayResponse(
                    selected,
                    replayedIntentIds.size(),
                    failures.size(),
                    skipped,
                    List.copyOf(replayedIntentIds),
                    List.copyOf(failures));
        }
    }

    private static final class DeleteAccumulator {
        private final int selected;
        private final List<String> deletedIntentIds = new ArrayList<>();
        private final List<DeadLetterDeleteFailure> failures = new ArrayList<>();
        private int skipped;

        private DeleteAccumulator(int selected) {
            this.selected = selected;
        }

        private boolean hasFailure() {
            return !failures.isEmpty();
        }

        private DeleteAccumulator deleted(WorkflowRunWakeupDeadLetter deadLetter) {
            deletedIntentIds.add(deadLetter.intentId());
            return this;
        }

        private DeleteAccumulator failed(WorkflowRunWakeupDeadLetter deadLetter, Throwable error) {
            failures.add(new DeadLetterDeleteFailure(deadLetter.intentId(), errorMessage(error)));
            return this;
        }

        private void skip(WorkflowRunWakeupDeadLetter ignored) {
            skipped++;
        }

        private DeadLetterDeleteResponse response() {
            return new DeadLetterDeleteResponse(
                    selected,
                    deletedIntentIds.size(),
                    failures.size(),
                    skipped,
                    List.copyOf(deletedIntentIds),
                    List.copyOf(failures));
        }
    }

    private static String errorMessage(Throwable error) {
        if (error == null) {
            return "unknown";
        }
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getName() : message;
    }
}
