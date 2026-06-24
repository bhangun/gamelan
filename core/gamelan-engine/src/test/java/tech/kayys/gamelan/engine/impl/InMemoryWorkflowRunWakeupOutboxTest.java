package tech.kayys.gamelan.engine.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import tech.kayys.gamelan.engine.event.WorkflowRunUpdateEvent;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupDeadLetter;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupDeadLetterReasons;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupIntent;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupOutbox.DeadLetterPurgePolicy;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupOutbox.DeadLetterPurgeResult;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupOutbox.DeadLetterQuery;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;

class InMemoryWorkflowRunWakeupOutboxTest {

    @Test
    void enqueue_coalescesPendingWakeupsByTenantAndRun() {
        InMemoryWorkflowRunWakeupOutbox outbox = new InMemoryWorkflowRunWakeupOutbox();

        WorkflowRunWakeupIntent first = outbox.enqueue(event("first")).await().indefinitely();
        WorkflowRunWakeupIntent second = outbox.enqueue(event("second")).await().indefinitely();

        assertNotEquals(first.id(), second.id());
        assertEquals(1, outbox.pending(10).await().indefinitely().size());
        assertEquals("second", outbox.pending(10).await().indefinitely().getFirst().event().reason());
    }

    @Test
    void markFailed_keepsIntentPendingWithAttemptMetadata() {
        InMemoryWorkflowRunWakeupOutbox outbox = new InMemoryWorkflowRunWakeupOutbox();
        WorkflowRunWakeupIntent intent = outbox.enqueue(event("retry")).await().indefinitely();

        outbox.markFailed(intent.id(), new IllegalStateException("transport down")).await().indefinitely();

        WorkflowRunWakeupIntent pending = outbox.pending(10).await().indefinitely().getFirst();
        assertEquals(1, pending.attempts());
        assertNotNull(pending.lastAttemptAt());
        assertTrue(pending.lastError().contains("transport down"));
    }

    @Test
    void markDelivered_removesIntentFromPendingSet() {
        InMemoryWorkflowRunWakeupOutbox outbox = new InMemoryWorkflowRunWakeupOutbox();
        WorkflowRunWakeupIntent intent = outbox.enqueue(event("delivered")).await().indefinitely();

        outbox.markDelivered(intent.id()).await().indefinitely();

        assertTrue(outbox.pending(10).await().indefinitely().isEmpty());
    }

    @Test
    void markDelivered_whenIntentWasReplacedDoesNotClearNewerPendingWakeup() {
        InMemoryWorkflowRunWakeupOutbox outbox = new InMemoryWorkflowRunWakeupOutbox();
        WorkflowRunWakeupIntent first = outbox.enqueue(event("first")).await().indefinitely();
        WorkflowRunWakeupIntent second = outbox.enqueue(event("second")).await().indefinitely();

        outbox.markDelivered(first.id(), first.event()).await().indefinitely();

        assertEquals(1, outbox.pending(10).await().indefinitely().size());
        assertEquals("second", outbox.pending(10).await().indefinitely().getFirst().event().reason());

        outbox.markDelivered(second.id(), second.event()).await().indefinitely();
        assertTrue(outbox.pending(10).await().indefinitely().isEmpty());
    }

    @Test
    void enqueue_whenCapacityFullRejectsNewRunButAllowsCoalescingExistingRun() {
        InMemoryWorkflowRunWakeupOutbox outbox = new InMemoryWorkflowRunWakeupOutbox();
        outbox.maxPendingWakeups = 1;
        outbox.enqueue(event("first")).await().indefinitely();

        outbox.enqueue(event("replacement")).await().indefinitely();

        assertEquals(1, outbox.pending(10).await().indefinitely().size());
        assertEquals("replacement", outbox.pending(10).await().indefinitely().getFirst().event().reason());
        assertThrows(IllegalStateException.class,
                () -> outbox.enqueue(event("other-run", WorkflowRunId.of("run-2"))).await().indefinitely());
    }

    @Test
    void markFailed_movesWakeupToDeadLettersWhenAttemptBudgetIsExceeded() {
        InMemoryWorkflowRunWakeupOutbox outbox = new InMemoryWorkflowRunWakeupOutbox();
        outbox.maxDeliveryAttempts = 1;
        WorkflowRunWakeupIntent intent = outbox.enqueue(event("poison")).await().indefinitely();

        outbox.markFailed(intent.id(), new IllegalStateException("transport stuck")).await().indefinitely();

        assertTrue(outbox.pending(10).await().indefinitely().isEmpty());
        assertEquals(1L, outbox.deadLetterCount().await().indefinitely());

        WorkflowRunWakeupDeadLetter deadLetter = outbox.deadLetters(10).await().indefinitely().getFirst();
        assertEquals(intent.id(), deadLetter.intentId());
        assertEquals("poison", deadLetter.event().reason());
        assertEquals(WorkflowRunWakeupDeadLetterReasons.MAX_DELIVERY_ATTEMPTS_EXCEEDED,
                deadLetter.deadLetterReason());
        assertEquals(1, deadLetter.attempts());
        assertTrue(deadLetter.lastError().contains("transport stuck"));
    }

    @Test
    void deadLettersWithQueryFiltersBeforeApplyingLimit() {
        InMemoryWorkflowRunWakeupOutbox outbox = new InMemoryWorkflowRunWakeupOutbox();
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
    void replayDeadLetter_requeuesFreshWakeupAndRemovesDeadLetter() {
        InMemoryWorkflowRunWakeupOutbox outbox = new InMemoryWorkflowRunWakeupOutbox();
        outbox.maxDeliveryAttempts = 1;
        WorkflowRunWakeupIntent intent = outbox.enqueue(event("replay")).await().indefinitely();
        outbox.markFailed(intent.id(), new IllegalStateException("temporary poison")).await().indefinitely();

        WorkflowRunWakeupIntent replayed = outbox.replayDeadLetter(intent.id())
                .await()
                .indefinitely()
                .orElseThrow();

        assertNotEquals(intent.id(), replayed.id());
        assertEquals("replay", replayed.event().reason());
        assertEquals(0, replayed.attempts());
        assertEquals(0L, outbox.deadLetterCount().await().indefinitely());
        assertEquals(replayed.id(), outbox.pending(10).await().indefinitely().getFirst().id());
    }

    @Test
    void purgeDeadLettersDryRunDoesNotDeleteCandidates() {
        InMemoryWorkflowRunWakeupOutbox outbox = new InMemoryWorkflowRunWakeupOutbox();
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
        InMemoryWorkflowRunWakeupOutbox outbox = new InMemoryWorkflowRunWakeupOutbox();
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

    private static WorkflowRunWakeupIntent deadLetter(
            InMemoryWorkflowRunWakeupOutbox outbox,
            String reason,
            WorkflowRunId runId) {
        WorkflowRunWakeupIntent intent = outbox.enqueue(event(reason, runId)).await().indefinitely();
        outbox.markFailed(intent.id(), new IllegalStateException(reason + " failure")).await().indefinitely();
        return intent;
    }

    private WorkflowRunUpdateEvent event(String reason) {
        return event(reason, WorkflowRunId.of("run-1"));
    }

    private static WorkflowRunUpdateEvent event(String reason, WorkflowRunId runId) {
        return WorkflowRunUpdateEvent.of(
                runId,
                TenantId.of("tenant-1"),
                reason);
    }
}
