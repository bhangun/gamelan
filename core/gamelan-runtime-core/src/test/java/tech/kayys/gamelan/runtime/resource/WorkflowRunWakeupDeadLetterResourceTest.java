package tech.kayys.gamelan.runtime.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import tech.kayys.gamelan.engine.event.WorkflowRunUpdateEvent;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupDeadLetterAuditEvent;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupDeadLetterAuditEvent.Operation;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupDeadLetterAuditEvent.Outcome;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupDeadLetterAuditSink;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupDeadLetter;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupDeadLetterReasons;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupIntent;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupOutbox;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupOutbox.DeadLetterPurgePolicy;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupOutbox.DeadLetterPurgeResult;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupOutbox.DeadLetterQuery;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;

class WorkflowRunWakeupDeadLetterResourceTest {

    @Test
    void listDelegatesToWakeupOutboxWithDefaultLimit() {
        RecordingWakeupOutbox outbox = new RecordingWakeupOutbox();
        WorkflowRunWakeupDeadLetter deadLetter = deadLetter("intent-1");
        outbox.deadLetters = List.of(deadLetter);
        WorkflowRunWakeupDeadLetterResource resource = resource(outbox);

        List<WorkflowRunWakeupDeadLetter> listed = resource.list(null, null, null, null, null).await().indefinitely();

        assertEquals(100, outbox.listQuery.limit());
        assertEquals(List.of(deadLetter), listed);
    }

    @Test
    void listUsesExplicitLimit() {
        RecordingWakeupOutbox outbox = new RecordingWakeupOutbox();
        WorkflowRunWakeupDeadLetterResource resource = resource(outbox);

        resource.list(25, null, null, null, null).await().indefinitely();

        assertEquals(25, outbox.listQuery.limit());
    }

    @Test
    void listPassesFiltersToWakeupOutbox() {
        RecordingWakeupOutbox outbox = new RecordingWakeupOutbox();
        WorkflowRunWakeupDeadLetterResource resource = resource(outbox);

        resource.list(25, " run-1 ", " tenant-1 ", " reason-1 ", " maxed ").await().indefinitely();

        assertEquals(25, outbox.listQuery.limit());
        assertEquals("run-1", outbox.listQuery.runId());
        assertEquals("tenant-1", outbox.listQuery.tenantId());
        assertEquals("reason-1", outbox.listQuery.reason());
        assertEquals("maxed", outbox.listQuery.deadLetterReason());
    }

    @Test
    void countDelegatesToWakeupOutbox() {
        RecordingWakeupOutbox outbox = new RecordingWakeupOutbox();
        outbox.deadLetterCount = 7L;
        WorkflowRunWakeupDeadLetterResource resource = resource(outbox);

        assertEquals(7L, resource.count(null, null, null, null).await().indefinitely());
        assertEquals(100, outbox.countQuery.limit());
    }

    @Test
    void countPassesFiltersToWakeupOutbox() {
        RecordingWakeupOutbox outbox = new RecordingWakeupOutbox();
        WorkflowRunWakeupDeadLetterResource resource = resource(outbox);

        resource.count("run-1", "tenant-1", "reason-1", "maxed").await().indefinitely();

        assertEquals("run-1", outbox.countQuery.runId());
        assertEquals("tenant-1", outbox.countQuery.tenantId());
        assertEquals("reason-1", outbox.countQuery.reason());
        assertEquals("maxed", outbox.countQuery.deadLetterReason());
    }

    @Test
    void replayReturnsRequeuedWakeup() {
        RecordingWakeupOutbox outbox = new RecordingWakeupOutbox();
        WorkflowRunWakeupIntent replayed = WorkflowRunWakeupIntent.pending(event("replayed"), Instant.now());
        outbox.replayed = Optional.of(replayed);
        WorkflowRunWakeupDeadLetterResource resource = resource(outbox);

        WorkflowRunWakeupIntent response = resource.replay(" intent-1 ").await().indefinitely();

        assertEquals(" intent-1 ", outbox.replayedIntentId);
        assertSame(replayed, response);
    }

    @Test
    void replayAuditsSuccessfulSingleReplay() {
        RecordingWakeupOutbox outbox = new RecordingWakeupOutbox();
        RecordingAuditSink auditSink = new RecordingAuditSink();
        outbox.replayed = Optional.of(WorkflowRunWakeupIntent.pending(event("replayed"), Instant.now()));
        WorkflowRunWakeupDeadLetterResource resource = resource(outbox, auditSink);

        resource.replay(" intent-1 ").await().indefinitely();

        WorkflowRunWakeupDeadLetterAuditEvent event = auditSink.events.getFirst();
        assertEquals(Operation.REPLAY, event.operation());
        assertEquals(Outcome.SUCCEEDED, event.outcome());
        assertEquals("intent-1", event.intentId());
        assertEquals(1, event.selected());
        assertEquals(1, event.succeeded());
        assertEquals(0, event.failed());
    }

    @Test
    void replayReturnsNotFoundWhenDeadLetterDoesNotExist() {
        WorkflowRunWakeupDeadLetterResource resource = resource(new RecordingWakeupOutbox());

        assertThrows(NotFoundException.class, () -> resource.replay("missing").await().indefinitely());
    }

    @Test
    void replayAuditsFailedSingleReplay() {
        RecordingAuditSink auditSink = new RecordingAuditSink();
        WorkflowRunWakeupDeadLetterResource resource = resource(new RecordingWakeupOutbox(), auditSink);

        assertThrows(NotFoundException.class, () -> resource.replay("missing").await().indefinitely());

        WorkflowRunWakeupDeadLetterAuditEvent event = auditSink.events.getFirst();
        assertEquals(Operation.REPLAY, event.operation());
        assertEquals(Outcome.FAILED, event.outcome());
        assertEquals("missing", event.intentId());
        assertEquals(0, event.succeeded());
        assertEquals(1, event.failed());
    }

    @Test
    void bulkReplayRequiresFilterOrExplicitAllFlag() {
        WorkflowRunWakeupDeadLetterResource resource = resource(new RecordingWakeupOutbox());

        assertThrows(BadRequestException.class,
                () -> resource.replayMatching(null, null, null, null, null, false).await().indefinitely());
    }

    @Test
    void bulkReplayReplaysSelectedDeadLetters() {
        RecordingWakeupOutbox outbox = new RecordingWakeupOutbox();
        WorkflowRunWakeupDeadLetter first = deadLetter("intent-1");
        WorkflowRunWakeupDeadLetter second = deadLetter("intent-2");
        outbox.deadLetters = List.of(first, second);
        outbox.replayResults.put("intent-1", Optional.of(WorkflowRunWakeupIntent.pending(first.event(), Instant.now())));
        outbox.replayResults.put("intent-2", Optional.of(WorkflowRunWakeupIntent.pending(second.event(), Instant.now())));
        WorkflowRunWakeupDeadLetterResource resource = resource(outbox);

        WorkflowRunWakeupDeadLetterResource.DeadLetterReplayResponse response = resource
                .replayMatching(10, "run-1", null, null, null, false)
                .await()
                .indefinitely();

        assertEquals("run-1", outbox.listQuery.runId());
        assertEquals(2, response.selected());
        assertEquals(2, response.replayed());
        assertEquals(0, response.failed());
        assertEquals(0, response.skipped());
        assertEquals(List.of("intent-1", "intent-2"), response.replayedIntentIds());
        assertEquals(List.of("intent-1", "intent-2"), outbox.replayedIntentIds);
    }

    @Test
    void bulkReplayAuditsAggregateOutcome() {
        RecordingWakeupOutbox outbox = new RecordingWakeupOutbox();
        RecordingAuditSink auditSink = new RecordingAuditSink();
        WorkflowRunWakeupDeadLetter first = deadLetter("intent-1");
        WorkflowRunWakeupDeadLetter second = deadLetter("intent-2");
        outbox.deadLetters = List.of(first, second);
        outbox.replayResults.put("intent-1", Optional.of(WorkflowRunWakeupIntent.pending(first.event(), Instant.now())));
        outbox.replayResults.put("intent-2", Optional.of(WorkflowRunWakeupIntent.pending(second.event(), Instant.now())));
        WorkflowRunWakeupDeadLetterResource resource = resource(outbox, auditSink);

        resource.replayMatching(10, "run-1", null, null, null, false).await().indefinitely();

        WorkflowRunWakeupDeadLetterAuditEvent event = auditSink.events.getFirst();
        assertEquals(Operation.BULK_REPLAY, event.operation());
        assertEquals(Outcome.SUCCEEDED, event.outcome());
        assertEquals("run-1", event.query().runId());
        assertEquals(2, event.selected());
        assertEquals(2, event.succeeded());
        assertEquals(List.of("intent-1", "intent-2"), event.intentIds());
    }

    @Test
    void bulkReplayStopsAfterFirstFailureAndReportsSkippedEntries() {
        RecordingWakeupOutbox outbox = new RecordingWakeupOutbox();
        WorkflowRunWakeupDeadLetter first = deadLetter("intent-1");
        WorkflowRunWakeupDeadLetter second = deadLetter("intent-2");
        outbox.deadLetters = List.of(first, second);
        outbox.replayFailure = new IllegalStateException("outbox full");
        WorkflowRunWakeupDeadLetterResource resource = resource(outbox);

        WorkflowRunWakeupDeadLetterResource.DeadLetterReplayResponse response = resource
                .replayMatching(10, "run-1", null, null, null, false)
                .await()
                .indefinitely();

        assertEquals(2, response.selected());
        assertEquals(0, response.replayed());
        assertEquals(1, response.failed());
        assertEquals(1, response.skipped());
        assertEquals("intent-1", response.failures().getFirst().intentId());
        assertEquals("outbox full", response.failures().getFirst().error());
        assertEquals(List.of("intent-1"), outbox.replayedIntentIds);
    }

    @Test
    void purgeRequiresFilterOrExplicitAllFlag() {
        WorkflowRunWakeupDeadLetterResource resource = resource(new RecordingWakeupOutbox());

        assertThrows(BadRequestException.class,
                () -> resource.purge(null, null, null, null, 3600L, null, true, false)
                        .await()
                        .indefinitely());
    }

    @Test
    void purgeRequiresRetentionCriteria() {
        WorkflowRunWakeupDeadLetterResource resource = resource(new RecordingWakeupOutbox());

        assertThrows(BadRequestException.class,
                () -> resource.purge("run-1", null, null, null, null, null, true, false)
                        .await()
                        .indefinitely());
    }

    @Test
    void purgeDefaultsToDryRunAndPassesRetentionPolicyToOutbox() {
        RecordingWakeupOutbox outbox = new RecordingWakeupOutbox();
        outbox.purgeResult = new DeadLetterPurgeResult(2, 0, true, List.of("intent-1", "intent-2"));
        WorkflowRunWakeupDeadLetterResource resource = resource(outbox);

        DeadLetterPurgeResult response = resource
                .purge(" run-1 ", " tenant-1 ", null, null, 3600L, 10, null, false)
                .await()
                .indefinitely();

        assertEquals("run-1", outbox.purgePolicy.query().runId());
        assertEquals("tenant-1", outbox.purgePolicy.query().tenantId());
        assertEquals(Duration.ofSeconds(3600), outbox.purgePolicy.olderThan());
        assertEquals(10, outbox.purgePolicy.retainLatest());
        assertEquals(true, outbox.purgePolicy.dryRun());
        assertEquals(outbox.purgeResult, response);
    }

    @Test
    void purgeAuditsDryRunResult() {
        RecordingWakeupOutbox outbox = new RecordingWakeupOutbox();
        RecordingAuditSink auditSink = new RecordingAuditSink();
        outbox.purgeResult = new DeadLetterPurgeResult(2, 0, true, List.of("intent-1", "intent-2"));
        WorkflowRunWakeupDeadLetterResource resource = resource(outbox, auditSink);

        resource.purge("run-1", null, null, null, 3600L, null, null, false).await().indefinitely();

        WorkflowRunWakeupDeadLetterAuditEvent event = auditSink.events.getFirst();
        assertEquals(Operation.PURGE, event.operation());
        assertEquals(Outcome.DRY_RUN, event.outcome());
        assertEquals(true, event.dryRun());
        assertEquals(2, event.selected());
        assertEquals(0, event.succeeded());
        assertEquals(List.of("intent-1", "intent-2"), event.intentIds());
    }

    @Test
    void purgeAllowsActualDeleteWhenDryRunIsFalse() {
        RecordingWakeupOutbox outbox = new RecordingWakeupOutbox();
        outbox.purgeResult = new DeadLetterPurgeResult(1, 1, false, List.of("intent-1"));
        WorkflowRunWakeupDeadLetterResource resource = resource(outbox);

        DeadLetterPurgeResult response = resource
                .purge(null, null, null, null, null, 0, false, true)
                .await()
                .indefinitely();

        assertEquals(false, outbox.purgePolicy.dryRun());
        assertEquals(0, outbox.purgePolicy.retainLatest());
        assertEquals(1, response.purged());
    }

    @Test
    void purgeRejectsNegativeRetentionInputs() {
        WorkflowRunWakeupDeadLetterResource resource = resource(new RecordingWakeupOutbox());

        assertThrows(BadRequestException.class,
                () -> resource.purge("run-1", null, null, null, -1L, null, true, false));
        assertThrows(BadRequestException.class,
                () -> resource.purge("run-1", null, null, null, null, -1, true, false));
    }

    @Test
    void bulkDeleteRequiresFilterOrExplicitAllFlag() {
        WorkflowRunWakeupDeadLetterResource resource = resource(new RecordingWakeupOutbox());

        assertThrows(BadRequestException.class,
                () -> resource.deleteMatching(null, null, null, null, null, false).await().indefinitely());
    }

    @Test
    void bulkDeleteDeletesSelectedDeadLetters() {
        RecordingWakeupOutbox outbox = new RecordingWakeupOutbox();
        WorkflowRunWakeupDeadLetter first = deadLetter("intent-1");
        WorkflowRunWakeupDeadLetter second = deadLetter("intent-2");
        outbox.deadLetters = List.of(first, second);
        outbox.deleteResults.put("intent-1", true);
        outbox.deleteResults.put("intent-2", true);
        WorkflowRunWakeupDeadLetterResource resource = resource(outbox);

        WorkflowRunWakeupDeadLetterResource.DeadLetterDeleteResponse response = resource
                .deleteMatching(10, "run-1", null, null, null, false)
                .await()
                .indefinitely();

        assertEquals("run-1", outbox.listQuery.runId());
        assertEquals(2, response.selected());
        assertEquals(2, response.deleted());
        assertEquals(0, response.failed());
        assertEquals(0, response.skipped());
        assertEquals(List.of("intent-1", "intent-2"), response.deletedIntentIds());
        assertEquals(List.of("intent-1", "intent-2"), outbox.deletedIntentIds);
    }

    @Test
    void bulkDeleteAuditsAggregateOutcome() {
        RecordingWakeupOutbox outbox = new RecordingWakeupOutbox();
        RecordingAuditSink auditSink = new RecordingAuditSink();
        WorkflowRunWakeupDeadLetter first = deadLetter("intent-1");
        WorkflowRunWakeupDeadLetter second = deadLetter("intent-2");
        outbox.deadLetters = List.of(first, second);
        outbox.deleteResults.put("intent-1", true);
        outbox.deleteResults.put("intent-2", true);
        WorkflowRunWakeupDeadLetterResource resource = resource(outbox, auditSink);

        resource.deleteMatching(10, "run-1", null, null, null, false).await().indefinitely();

        WorkflowRunWakeupDeadLetterAuditEvent event = auditSink.events.getFirst();
        assertEquals(Operation.BULK_DELETE, event.operation());
        assertEquals(Outcome.SUCCEEDED, event.outcome());
        assertEquals(2, event.selected());
        assertEquals(2, event.succeeded());
        assertEquals(List.of("intent-1", "intent-2"), event.intentIds());
    }

    @Test
    void bulkDeleteStopsAfterFirstFailureAndReportsSkippedEntries() {
        RecordingWakeupOutbox outbox = new RecordingWakeupOutbox();
        WorkflowRunWakeupDeadLetter first = deadLetter("intent-1");
        WorkflowRunWakeupDeadLetter second = deadLetter("intent-2");
        outbox.deadLetters = List.of(first, second);
        outbox.deleteFailure = new IllegalStateException("delete failed");
        WorkflowRunWakeupDeadLetterResource resource = resource(outbox);

        WorkflowRunWakeupDeadLetterResource.DeadLetterDeleteResponse response = resource
                .deleteMatching(10, "run-1", null, null, null, false)
                .await()
                .indefinitely();

        assertEquals(2, response.selected());
        assertEquals(0, response.deleted());
        assertEquals(1, response.failed());
        assertEquals(1, response.skipped());
        assertEquals("intent-1", response.failures().getFirst().intentId());
        assertEquals("delete failed", response.failures().getFirst().error());
        assertEquals(List.of("intent-1"), outbox.deletedIntentIds);
    }

    @Test
    void deleteRemovesDeadLetter() {
        RecordingWakeupOutbox outbox = new RecordingWakeupOutbox();
        outbox.deleteResult = true;
        WorkflowRunWakeupDeadLetterResource resource = resource(outbox);

        resource.delete(" intent-1 ").await().indefinitely();

        assertEquals(" intent-1 ", outbox.deletedIntentId);
    }

    @Test
    void deleteAuditsSuccessfulSingleDelete() {
        RecordingWakeupOutbox outbox = new RecordingWakeupOutbox();
        RecordingAuditSink auditSink = new RecordingAuditSink();
        outbox.deleteResult = true;
        WorkflowRunWakeupDeadLetterResource resource = resource(outbox, auditSink);

        resource.delete(" intent-1 ").await().indefinitely();

        WorkflowRunWakeupDeadLetterAuditEvent event = auditSink.events.getFirst();
        assertEquals(Operation.DELETE, event.operation());
        assertEquals(Outcome.SUCCEEDED, event.outcome());
        assertEquals("intent-1", event.intentId());
        assertEquals(1, event.succeeded());
    }

    @Test
    void auditSinkFailureDoesNotBlockDelete() {
        RecordingWakeupOutbox outbox = new RecordingWakeupOutbox();
        RecordingAuditSink auditSink = new RecordingAuditSink();
        outbox.deleteResult = true;
        auditSink.failure = new IllegalStateException("audit unavailable");
        WorkflowRunWakeupDeadLetterResource resource = resource(outbox, auditSink);

        resource.delete("intent-1").await().indefinitely();

        assertEquals("intent-1", outbox.deletedIntentId);
    }

    @Test
    void deleteReturnsNotFoundWhenDeadLetterDoesNotExist() {
        WorkflowRunWakeupDeadLetterResource resource = resource(new RecordingWakeupOutbox());

        assertThrows(NotFoundException.class, () -> resource.delete("missing").await().indefinitely());
    }

    private static WorkflowRunWakeupDeadLetterResource resource(RecordingWakeupOutbox outbox) {
        return resource(outbox, null);
    }

    private static WorkflowRunWakeupDeadLetterResource resource(
            RecordingWakeupOutbox outbox,
            RecordingAuditSink auditSink) {
        WorkflowRunWakeupDeadLetterResource resource = new WorkflowRunWakeupDeadLetterResource();
        resource.wakeupOutbox = outbox;
        resource.auditSink = auditSink;
        return resource;
    }

    private static WorkflowRunWakeupDeadLetter deadLetter(String intentId) {
        return new WorkflowRunWakeupDeadLetter(
                intentId,
                event("dead-lettered"),
                WorkflowRunWakeupDeadLetterReasons.MAX_DELIVERY_ATTEMPTS_EXCEEDED,
                100,
                Instant.now().minusSeconds(60),
                Instant.now().minusSeconds(5),
                "transport failed",
                Instant.now());
    }

    private static WorkflowRunUpdateEvent event(String reason) {
        return WorkflowRunUpdateEvent.of(
                WorkflowRunId.of("run-1"),
                TenantId.of("tenant-1"),
                reason);
    }

    private static final class RecordingWakeupOutbox implements WorkflowRunWakeupOutbox {
        private List<WorkflowRunWakeupDeadLetter> deadLetters = List.of();
        private long deadLetterCount;
        private DeadLetterQuery listQuery;
        private DeadLetterQuery countQuery;
        private Optional<WorkflowRunWakeupIntent> replayed = Optional.empty();
        private String replayedIntentId;
        private final List<String> replayedIntentIds = new java.util.ArrayList<>();
        private final Map<String, Optional<WorkflowRunWakeupIntent>> replayResults = new HashMap<>();
        private RuntimeException replayFailure;
        private DeadLetterPurgePolicy purgePolicy;
        private DeadLetterPurgeResult purgeResult = DeadLetterPurgeResult.empty(true);
        private boolean deleteResult;
        private final Map<String, Boolean> deleteResults = new HashMap<>();
        private RuntimeException deleteFailure;
        private String deletedIntentId;
        private final List<String> deletedIntentIds = new java.util.ArrayList<>();

        @Override
        public Uni<WorkflowRunWakeupIntent> enqueue(WorkflowRunUpdateEvent event) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Uni<List<WorkflowRunWakeupIntent>> pending(int maxItems) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Uni<List<WorkflowRunWakeupDeadLetter>> deadLetters(DeadLetterQuery query) {
            listQuery = query;
            return Uni.createFrom().item(deadLetters);
        }

        @Override
        public Uni<Long> deadLetterCount(DeadLetterQuery query) {
            countQuery = query;
            return Uni.createFrom().item(deadLetterCount);
        }

        @Override
        public Uni<Optional<WorkflowRunWakeupIntent>> replayDeadLetter(String intentId) {
            replayedIntentId = intentId;
            replayedIntentIds.add(intentId);
            if (replayFailure != null) {
                return Uni.createFrom().failure(replayFailure);
            }
            return Uni.createFrom().item(replayResults.getOrDefault(intentId, replayed));
        }

        @Override
        public Uni<DeadLetterPurgeResult> purgeDeadLetters(DeadLetterPurgePolicy policy) {
            purgePolicy = policy;
            return Uni.createFrom().item(purgeResult);
        }

        @Override
        public Uni<Boolean> deleteDeadLetter(String intentId) {
            deletedIntentId = intentId;
            deletedIntentIds.add(intentId);
            if (deleteFailure != null) {
                return Uni.createFrom().failure(deleteFailure);
            }
            return Uni.createFrom().item(deleteResults.getOrDefault(intentId, deleteResult));
        }

        @Override
        public Uni<Void> markDelivered(String intentId, WorkflowRunUpdateEvent deliveredEvent) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Uni<Void> markFailed(String intentId, Throwable error) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class RecordingAuditSink implements WorkflowRunWakeupDeadLetterAuditSink {
        private final List<WorkflowRunWakeupDeadLetterAuditEvent> events = new java.util.ArrayList<>();
        private RuntimeException failure;

        @Override
        public Uni<Void> append(WorkflowRunWakeupDeadLetterAuditEvent event) {
            if (failure != null) {
                return Uni.createFrom().failure(failure);
            }
            events.add(event);
            return Uni.createFrom().voidItem();
        }
    }
}
