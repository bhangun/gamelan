package tech.kayys.gamelan.runtime.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tech.kayys.gamelan.engine.event.WorkflowRunUpdateEvent;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupDeadLetter;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupDeadLetterReasons;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupIntent;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupOutbox.DeadLetterPurgePolicy;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupOutbox.DeadLetterPurgeResult;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupOutbox.DeadLetterQuery;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;

class FileWorkflowRunWakeupOutboxTest {

    @TempDir
    Path tempDir;

    @Test
    void persistsWakeupsAcrossInstancesAndSupportsRetryAndDelivery() {
        FileWorkflowRunWakeupOutbox writer = outbox("engine-a");
        WorkflowRunWakeupIntent intent = writer.enqueue(event("queued")).await().indefinitely();

        FileWorkflowRunWakeupOutbox reader = outbox("engine-a");
        assertEquals(1, reader.pending(10).await().indefinitely().size());
        assertEquals("queued", reader.claimPending(10).await().indefinitely().getFirst().event().reason());

        reader.markFailed(intent.id(), new IllegalStateException("event bus down")).await().indefinitely();

        FileWorkflowRunWakeupOutbox afterFailure = outbox("engine-a");
        afterFailure.retryBackoff = Duration.ZERO;
        WorkflowRunWakeupIntent retried = afterFailure.claimPending(10).await().indefinitely().getFirst();
        assertEquals(1, retried.attempts());
        assertTrue(retried.lastError().contains("event bus down"));

        afterFailure.markDelivered(retried.id(), retried.event()).await().indefinitely();

        FileWorkflowRunWakeupOutbox afterDelivery = outbox("engine-a");
        assertTrue(afterDelivery.pending(10).await().indefinitely().isEmpty());
    }

    @Test
    void enqueue_coalescesByTenantRunButReplacesIntentIdentity() {
        FileWorkflowRunWakeupOutbox outbox = outbox("engine-a");

        WorkflowRunWakeupIntent first = outbox.enqueue(event("first")).await().indefinitely();
        WorkflowRunWakeupIntent second = outbox.enqueue(event("second")).await().indefinitely();

        assertNotEquals(first.id(), second.id());
        assertEquals(1, outbox.pending(10).await().indefinitely().size());
        assertEquals("second", outbox.pending(10).await().indefinitely().getFirst().event().reason());
    }

    @Test
    void staleDeliveryAndFailureDoNotMutateReplacementWakeup() {
        FileWorkflowRunWakeupOutbox outbox = outbox("engine-a");
        WorkflowRunWakeupIntent first = outbox.enqueue(event("first")).await().indefinitely();
        WorkflowRunWakeupIntent second = outbox.enqueue(event("second")).await().indefinitely();

        outbox.markFailed(first.id(), new IllegalStateException("stale failure")).await().indefinitely();
        outbox.markDelivered(first.id(), first.event()).await().indefinitely();

        WorkflowRunWakeupIntent pending = outbox.pending(10).await().indefinitely().getFirst();
        assertEquals(second.id(), pending.id());
        assertEquals("second", pending.event().reason());
        assertEquals(0, pending.attempts());

        outbox.markDelivered(second.id(), second.event()).await().indefinitely();
        assertTrue(outbox.pending(10).await().indefinitely().isEmpty());
    }

    @Test
    void enqueue_whenCapacityFullRejectsNewRunButAllowsReplacement() {
        FileWorkflowRunWakeupOutbox outbox = outbox("engine-a");
        outbox.maxPendingWakeups = 1;

        outbox.enqueue(event("first")).await().indefinitely();
        outbox.enqueue(event("replacement")).await().indefinitely();

        assertEquals(1, outbox.pending(10).await().indefinitely().size());
        assertEquals("replacement", outbox.pending(10).await().indefinitely().getFirst().event().reason());
        assertThrows(IllegalStateException.class,
                () -> outbox.enqueue(event("other", WorkflowRunId.of("run-2"))).await().indefinitely());
    }

    @Test
    void claimPending_skipsWakeupLeasedByOtherOwnerButAllowsReadOnlyInspection() {
        FileWorkflowRunWakeupOutbox ownerA = outbox("engine-a");
        ownerA.leaseDuration = Duration.ofHours(1);
        ownerA.enqueue(event("leased")).await().indefinitely();

        FileWorkflowRunWakeupOutbox ownerB = outbox("engine-b");

        assertEquals("leased", ownerB.pending(10).await().indefinitely().getFirst().event().reason());
        assertTrue(ownerB.claimPending(10).await().indefinitely().isEmpty());
        assertEquals("leased", ownerA.claimPending(10).await().indefinitely().getFirst().event().reason());
    }

    @Test
    void claimPending_reclaimsExpiredLeaseFromOtherOwner() throws Exception {
        FileWorkflowRunWakeupOutbox ownerA = outbox("engine-a");
        ownerA.leaseDuration = Duration.ofMillis(1);
        ownerA.enqueue(event("expired")).await().indefinitely();

        Thread.sleep(20);

        FileWorkflowRunWakeupOutbox ownerB = outbox("engine-b");

        assertEquals("expired", ownerB.claimPending(10).await().indefinitely().getFirst().event().reason());
    }

    @Test
    void claimPending_skipsFailedWakeupUntilRetryBackoffElapsed() {
        FileWorkflowRunWakeupOutbox outbox = outbox("engine-a");
        outbox.retryBackoff = Duration.ofHours(1);
        WorkflowRunWakeupIntent intent = outbox.enqueue(event("failed")).await().indefinitely();

        outbox.markFailed(intent.id(), new IllegalStateException("temporary outage")).await().indefinitely();

        assertTrue(outbox.claimPending(10).await().indefinitely().isEmpty());

        outbox.retryBackoff = Duration.ZERO;
        WorkflowRunWakeupIntent retried = outbox.claimPending(10).await().indefinitely().getFirst();
        assertEquals("failed", retried.event().reason());
        assertEquals(1, retried.attempts());
    }

    @Test
    void markFailed_movesWakeupToDeadLettersWhenAttemptBudgetIsExceeded() {
        FileWorkflowRunWakeupOutbox outbox = outbox("engine-a");
        outbox.maxDeliveryAttempts = 2;
        outbox.retryBackoff = Duration.ZERO;
        WorkflowRunWakeupIntent intent = outbox.enqueue(event("poison")).await().indefinitely();

        outbox.markFailed(intent.id(), new IllegalStateException("first failure")).await().indefinitely();

        WorkflowRunWakeupIntent retried = outbox.claimPending(10).await().indefinitely().getFirst();
        assertEquals(1, retried.attempts());

        outbox.markFailed(retried.id(), new IllegalStateException("second failure")).await().indefinitely();

        assertTrue(outbox.pending(10).await().indefinitely().isEmpty());
        assertTrue(outbox.claimPending(10).await().indefinitely().isEmpty());
        assertEquals(1L, outbox.deadLetterCount().await().indefinitely());

        WorkflowRunWakeupDeadLetter deadLetter = outbox.deadLetters(10).await().indefinitely().getFirst();
        assertEquals(retried.id(), deadLetter.intentId());
        assertEquals("poison", deadLetter.event().reason());
        assertEquals(WorkflowRunWakeupDeadLetterReasons.MAX_DELIVERY_ATTEMPTS_EXCEEDED,
                deadLetter.deadLetterReason());
        assertEquals(2, deadLetter.attempts());
        assertTrue(deadLetter.lastError().contains("second failure"));
    }

    @Test
    void replayDeadLetter_requeuesFreshWakeupAndRemovesDeadLetter() {
        FileWorkflowRunWakeupOutbox outbox = outbox("engine-a");
        outbox.maxDeliveryAttempts = 1;
        WorkflowRunWakeupIntent intent = outbox.enqueue(event("replay")).await().indefinitely();

        outbox.markFailed(intent.id(), new IllegalStateException("poison")).await().indefinitely();

        WorkflowRunWakeupIntent replayed = outbox.replayDeadLetter(intent.id())
                .await().indefinitely()
                .orElseThrow();

        assertNotEquals(intent.id(), replayed.id());
        assertEquals("replay", replayed.event().reason());
        assertEquals(0, replayed.attempts());
        assertEquals(0L, outbox.deadLetterCount().await().indefinitely());

        WorkflowRunWakeupIntent pending = outbox.pending(10).await().indefinitely().getFirst();
        assertEquals(replayed.id(), pending.id());
        assertEquals("replay", pending.event().reason());
    }

    @Test
    void deadLettersWithQueryFiltersBeforeApplyingLimit() {
        FileWorkflowRunWakeupOutbox outbox = outbox("engine-a");
        outbox.maxDeliveryAttempts = 1;
        WorkflowRunWakeupIntent target = outbox.enqueue(event("target", WorkflowRunId.of("run-1")))
                .await()
                .indefinitely();
        WorkflowRunWakeupIntent other = outbox.enqueue(event("other", WorkflowRunId.of("run-2")))
                .await()
                .indefinitely();

        outbox.markFailed(target.id(), new IllegalStateException("target failure")).await().indefinitely();
        outbox.markFailed(other.id(), new IllegalStateException("other failure")).await().indefinitely();

        var filtered = outbox.deadLetters(new DeadLetterQuery(1, "run-1", null, null, null))
                .await()
                .indefinitely();

        assertEquals(1, filtered.size());
        assertEquals("run-1", filtered.getFirst().event().runId());
        assertEquals(1L, outbox.deadLetterCount(new DeadLetterQuery(100, "run-1", null, null, null))
                .await()
                .indefinitely());
    }

    @Test
    void deleteDeadLetter_removesQuarantinedWakeupWithoutReplay() {
        FileWorkflowRunWakeupOutbox outbox = outbox("engine-a");
        outbox.maxDeliveryAttempts = 1;
        WorkflowRunWakeupIntent intent = outbox.enqueue(event("delete-dead")).await().indefinitely();

        outbox.markFailed(intent.id(), new IllegalStateException("poison")).await().indefinitely();

        assertTrue(outbox.deleteDeadLetter(intent.id()).await().indefinitely());
        assertFalse(outbox.deleteDeadLetter(intent.id()).await().indefinitely());
        assertEquals(0L, outbox.deadLetterCount().await().indefinitely());
        assertTrue(outbox.pending(10).await().indefinitely().isEmpty());
    }

    @Test
    void purgeDeadLettersDryRunDoesNotDeleteCandidates() {
        FileWorkflowRunWakeupOutbox outbox = outbox("engine-a");
        outbox.maxDeliveryAttempts = 1;
        deadLetter(outbox, "first", WorkflowRunId.of("run-1"));
        deadLetter(outbox, "second", WorkflowRunId.of("run-2"));

        DeadLetterPurgeResult result = outbox.purgeDeadLetters(new DeadLetterPurgePolicy(
                DeadLetterQuery.all(),
                Duration.ZERO,
                -1,
                true))
                .await()
                .indefinitely();

        assertEquals(2, result.selected());
        assertEquals(0, result.purged());
        assertEquals(true, result.dryRun());
        assertEquals(2L, outbox.deadLetterCount().await().indefinitely());
    }

    @Test
    void purgeDeadLettersDeletesOnlyFilteredCandidates() {
        FileWorkflowRunWakeupOutbox outbox = outbox("engine-a");
        outbox.maxDeliveryAttempts = 1;
        deadLetter(outbox, "first", WorkflowRunId.of("run-1"));
        deadLetter(outbox, "second", WorkflowRunId.of("run-2"));

        DeadLetterPurgeResult result = outbox.purgeDeadLetters(new DeadLetterPurgePolicy(
                new DeadLetterQuery(100, "run-1", null, null, null),
                Duration.ZERO,
                -1,
                false))
                .await()
                .indefinitely();

        assertEquals(1, result.selected());
        assertEquals(1, result.purged());
        assertEquals(1L, outbox.deadLetterCount().await().indefinitely());
        assertEquals(0L, outbox.deadLetterCount(new DeadLetterQuery(100, "run-1", null, null, null))
                .await()
                .indefinitely());
    }

    @Test
    void deliveryAndFailureRequireLeaseOwner() {
        FileWorkflowRunWakeupOutbox ownerA = outbox("engine-a");
        WorkflowRunWakeupIntent intent = ownerA.enqueue(event("owned")).await().indefinitely();
        FileWorkflowRunWakeupOutbox ownerB = outbox("engine-b");

        ownerB.markFailed(intent.id(), new IllegalStateException("wrong owner")).await().indefinitely();
        ownerB.markDelivered(intent.id(), intent.event()).await().indefinitely();

        WorkflowRunWakeupIntent stillPending = ownerA.pending(10).await().indefinitely().getFirst();
        assertEquals(intent.id(), stillPending.id());
        assertEquals(0, stillPending.attempts());

        ownerA.markDelivered(intent.id(), intent.event()).await().indefinitely();
        assertTrue(ownerA.pending(10).await().indefinitely().isEmpty());
    }

    @Test
    void hashesWakeupKeysToAvoidPathTraversal() {
        String outsideName = "wakeup-outside-" + System.nanoTime();
        WorkflowRunUpdateEvent pathLikeEvent = WorkflowRunUpdateEvent.of(
                WorkflowRunId.of("../../" + outsideName + "/run"),
                TenantId.of("../../" + outsideName + "/tenant"),
                "path-like");
        FileWorkflowRunWakeupOutbox outbox = outbox("engine-a");

        WorkflowRunWakeupIntent intent = outbox.enqueue(pathLikeEvent).await().indefinitely();

        assertEquals(pathLikeEvent, outbox.pending(10).await().indefinitely().getFirst().event());
        assertTrue(Files.isRegularFile(tempDir.resolve(FilePersistenceSupport.fileName(coalesceKey(intent.event())))));
        assertFalse(Files.exists(tempDir.getParent().resolve(outsideName)));
    }

    private WorkflowRunUpdateEvent event(String reason) {
        return event(reason, WorkflowRunId.of("run-1"));
    }

    private WorkflowRunUpdateEvent event(String reason, WorkflowRunId runId) {
        return WorkflowRunUpdateEvent.of(
                runId,
                TenantId.of("tenant-1"),
                reason);
    }

    private WorkflowRunWakeupIntent deadLetter(
            FileWorkflowRunWakeupOutbox outbox,
            String reason,
            WorkflowRunId runId) {
        WorkflowRunWakeupIntent intent = outbox.enqueue(event(reason, runId)).await().indefinitely();
        outbox.markFailed(intent.id(), new IllegalStateException(reason + " failure")).await().indefinitely();
        return intent;
    }

    private static String coalesceKey(WorkflowRunUpdateEvent event) {
        return (event.tenantId() != null ? event.tenantId() : "") + ":" + event.runId();
    }

    private FileWorkflowRunWakeupOutbox outbox(String ownerId) {
        FileWorkflowRunWakeupOutbox outbox = new FileWorkflowRunWakeupOutbox(tempDir);
        outbox.configuredOwnerId = ownerId;
        return outbox;
    }
}
