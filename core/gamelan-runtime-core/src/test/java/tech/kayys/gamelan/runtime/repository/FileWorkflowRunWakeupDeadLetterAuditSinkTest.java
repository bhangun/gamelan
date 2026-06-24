package tech.kayys.gamelan.runtime.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tech.kayys.gamelan.engine.event.WorkflowRunWakeupDeadLetterAuditEvent;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupDeadLetterAuditEvent.Operation;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupDeadLetterAuditEvent.Outcome;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupDeadLetterAuditSink.AuditPurgePolicy;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupDeadLetterAuditSink.AuditPurgeResult;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupDeadLetterAuditSink.AuditQuery;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupDeadLetterAuditSink.AuditSummary;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupOutbox.DeadLetterQuery;

class FileWorkflowRunWakeupDeadLetterAuditSinkTest {

    @TempDir
    Path tempDir;

    @Test
    void appendPersistsAuditEventAsImmutableJsonRecord() {
        FileWorkflowRunWakeupDeadLetterAuditSink sink = new FileWorkflowRunWakeupDeadLetterAuditSink(tempDir);
        WorkflowRunWakeupDeadLetterAuditEvent event = WorkflowRunWakeupDeadLetterAuditEvent.bulk(
                Operation.PURGE,
                new DeadLetterQuery(100, "run-1", "tenant-1", null, null),
                2,
                0,
                0,
                0,
                true,
                List.of("intent-1", "intent-2"),
                null);

        sink.append(event).await().indefinitely();

        List<Path> files = FilePersistenceSupport.listJsonFiles(tempDir);
        assertEquals(1, files.size());

        WorkflowRunWakeupDeadLetterAuditEvent restored = FilePersistenceSupport.read(
                files.getFirst(),
                WorkflowRunWakeupDeadLetterAuditEvent.class,
                FilePersistenceSupport.objectMapper());
        assertEquals(Operation.PURGE, restored.operation());
        assertEquals(event.outcome(), restored.outcome());
        assertEquals("run-1", restored.query().runId());
        assertEquals("tenant-1", restored.query().tenantId());
        assertEquals(2, restored.selected());
        assertEquals(true, restored.dryRun());
        assertEquals(List.of("intent-1", "intent-2"), restored.intentIds());
    }

    @Test
    void entriesAndCountApplyFiltersBeforeLimit() {
        FileWorkflowRunWakeupDeadLetterAuditSink sink = new FileWorkflowRunWakeupDeadLetterAuditSink(tempDir);
        sink.append(event(Operation.PURGE, "run-1", "tenant-1", "intent-1", Instant.parse("2026-06-08T00:00:02Z")))
                .await()
                .indefinitely();
        sink.append(event(Operation.DELETE, "run-2", "tenant-1", "intent-2", Instant.parse("2026-06-08T00:00:01Z")))
                .await()
                .indefinitely();

        AuditQuery query = new AuditQuery(
                1,
                Operation.PURGE,
                Outcome.SUCCEEDED,
                null,
                "run-1",
                "tenant-1",
                false,
                Instant.parse("2026-06-08T00:00:00Z"),
                Instant.parse("2026-06-08T00:00:03Z"));

        List<WorkflowRunWakeupDeadLetterAuditEvent> entries = sink.entries(query).await().indefinitely();

        assertEquals(1, entries.size());
        assertEquals(Operation.PURGE, entries.getFirst().operation());
        assertEquals("run-1", entries.getFirst().query().runId());
        assertEquals(1L, sink.count(query).await().indefinitely());

        AuditSummary summary = sink.summary(new AuditQuery(
                100,
                null,
                null,
                null,
                null,
                "tenant-1",
                false,
                null,
                null))
                .await()
                .indefinitely();
        assertEquals(2, summary.totalEvents());
        assertEquals(2, summary.selected());
        assertEquals(2, summary.succeeded());
        assertEquals(0, summary.failed());
        assertEquals(2, summary.buckets().size());
    }

    @Test
    void purgeDryRunSelectsCandidatesWithoutDeletingFiles() {
        FileWorkflowRunWakeupDeadLetterAuditSink sink = new FileWorkflowRunWakeupDeadLetterAuditSink(tempDir);
        sink.append(event(Operation.PURGE, "run-1", "tenant-1", "intent-1", Instant.EPOCH.plusSeconds(1)))
                .await()
                .indefinitely();
        sink.append(event(Operation.DELETE, "run-2", "tenant-1", "intent-2", Instant.EPOCH.plusSeconds(2)))
                .await()
                .indefinitely();

        AuditPurgeResult result = sink.purge(new AuditPurgePolicy(
                new AuditQuery(100, null, null, null, null, "tenant-1", null, null, null),
                Duration.ZERO,
                -1,
                true))
                .await()
                .indefinitely();

        assertEquals(2, result.selected());
        assertEquals(0, result.purged());
        assertEquals(true, result.dryRun());
        assertEquals(2, result.auditIds().size());
        assertEquals(2L, sink.count(AuditQuery.all()).await().indefinitely());
        assertEquals(2, FilePersistenceSupport.listJsonFiles(tempDir).size());
    }

    @Test
    void purgeDeletesOnlyFilteredCandidates() {
        FileWorkflowRunWakeupDeadLetterAuditSink sink = new FileWorkflowRunWakeupDeadLetterAuditSink(tempDir);
        sink.append(event(Operation.PURGE, "run-1", "tenant-1", "intent-1", Instant.EPOCH.plusSeconds(1)))
                .await()
                .indefinitely();
        sink.append(event(Operation.DELETE, "run-2", "tenant-1", "intent-2", Instant.EPOCH.plusSeconds(2)))
                .await()
                .indefinitely();

        AuditPurgeResult result = sink.purge(new AuditPurgePolicy(
                new AuditQuery(100, Operation.PURGE, null, null, "run-1", null, null, null, null),
                Duration.ZERO,
                -1,
                false))
                .await()
                .indefinitely();

        assertEquals(1, result.selected());
        assertEquals(1, result.purged());
        assertEquals(false, result.dryRun());
        assertEquals(1, result.auditIds().size());
        assertEquals(1L, sink.count(AuditQuery.all()).await().indefinitely());
        assertEquals(0L, sink.count(new AuditQuery(
                100,
                Operation.PURGE,
                null,
                null,
                "run-1",
                null,
                null,
                null,
                null))
                .await()
                .indefinitely());
        assertEquals(Operation.DELETE, sink.entries(AuditQuery.all()).await().indefinitely().getFirst().operation());
    }

    private static WorkflowRunWakeupDeadLetterAuditEvent event(
            Operation operation,
            String runId,
            String tenantId,
            String intentId,
            Instant occurredAt) {
        return new WorkflowRunWakeupDeadLetterAuditEvent(
                operation,
                Outcome.SUCCEEDED,
                null,
                new DeadLetterQuery(100, runId, tenantId, null, null),
                1,
                1,
                0,
                0,
                false,
                List.of(intentId),
                null,
                occurredAt);
    }
}
