package tech.kayys.gamelan.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.PreparedQuery;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.RowIterator;
import io.vertx.mutiny.sqlclient.RowSet;
import io.vertx.mutiny.sqlclient.Tuple;
import tech.kayys.gamelan.engine.event.WorkflowRunUpdateEvent;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupDeadLetter;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupDeadLetterReasons;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupIntent;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupOutbox.DeadLetterPurgePolicy;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupOutbox.DeadLetterPurgeResult;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupOutbox.DeadLetterQuery;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;

class PostgresWorkflowRunWakeupOutboxTest {

    private Pool pgPool;

    @SuppressWarnings("rawtypes")
    private PreparedQuery preparedQuery;

    private PostgresWorkflowRunWakeupOutbox outbox;
    private ObjectMapper objectMapper;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        pgPool = mock(Pool.class);
        preparedQuery = mock(PreparedQuery.class);
        objectMapper = new ObjectMapper().findAndRegisterModules();

        when(pgPool.preparedQuery(anyString())).thenReturn(preparedQuery);
        setRows();

        outbox = new PostgresWorkflowRunWakeupOutbox();
        outbox.pgPool = pgPool;
        outbox.objectMapper = objectMapper;
        outbox.configuredOwnerId = "engine-a";
        outbox.leaseDuration = Duration.ofSeconds(30);
        outbox.retryBackoff = Duration.ofSeconds(5);
        outbox.maxDeliveryAttempts = 100;
    }

    @Test
    void enqueueUpsertsWakeupIntentWithCapacityGate() throws Exception {
        WorkflowRunUpdateEvent event = event("queued");
        setRows(row(new WorkflowRunWakeupIntent(
                "intent-1",
                event,
                0,
                Instant.EPOCH,
                null,
                null,
                null)));

        WorkflowRunWakeupIntent intent = outbox.enqueue(event).await().indefinitely();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Tuple> tuple = ArgumentCaptor.forClass(Tuple.class);
        verify(pgPool).preparedQuery(sql.capture());
        verify(preparedQuery).execute(tuple.capture());

        assertTrue(sql.getValue().contains("INSERT INTO workflow_run_wakeup_outbox"));
        assertTrue(sql.getValue().contains("ON CONFLICT (wakeup_key)"));
        assertTrue(sql.getValue().contains("COUNT(*) < $7::int"));
        assertTrue(sql.getValue().contains("lease_owner"));
        assertTrue(sql.getValue().contains("lease_expires_at"));
        assertEquals("tenant-1:run-1", tuple.getValue().getString(0));
        assertEquals("run-1", tuple.getValue().getString(2));
        assertEquals("tenant-1", tuple.getValue().getString(3));
        assertEquals("queued", tuple.getValue().getString(4));
        assertTrue(tuple.getValue().getString(5).contains("\"runId\":\"run-1\""));
        assertEquals(10_000, tuple.getValue().getInteger(6));
        assertEquals("engine-a", tuple.getValue().getString(7));
        assertEquals(30_000L, tuple.getValue().getLong(8));
        assertEquals("queued", intent.event().reason());
    }

    @Test
    void enqueueFailsWhenCapacityGateReturnsNoRows() {
        assertThrows(IllegalStateException.class,
                () -> outbox.enqueue(event("full")).await().indefinitely());
    }

    @Test
    void pendingDeserializesStoredWakeupEventsInCreatedOrder() throws Exception {
        WorkflowRunUpdateEvent event = event("pending");
        setRows(row(new WorkflowRunWakeupIntent(
                "intent-1",
                event,
                2,
                Instant.EPOCH,
                Instant.EPOCH.plusSeconds(1),
                "transport failed",
                null)));

        WorkflowRunWakeupIntent pending = outbox.pending(25).await().indefinitely().getFirst();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Tuple> tuple = ArgumentCaptor.forClass(Tuple.class);
        verify(pgPool).preparedQuery(sql.capture());
        verify(preparedQuery).execute(tuple.capture());

        assertTrue(sql.getValue().contains("SELECT intent_id, event_payload, attempts"));
        assertTrue(sql.getValue().contains("ORDER BY created_at ASC"));
        assertTrue(sql.getValue().contains("LIMIT $1::int"));
        assertFalse(sql.getValue().contains("FOR UPDATE SKIP LOCKED"));
        assertFalse(sql.getValue().contains("lease_owner = $3"));
        assertEquals(25, tuple.getValue().getInteger(0));
        assertEquals("intent-1", pending.id());
        assertEquals("pending", pending.event().reason());
        assertEquals(2, pending.attempts());
        assertEquals("transport failed", pending.lastError());
    }

    @Test
    void claimPendingClaimsStoredWakeupEventsWithLeaseOwner() throws Exception {
        WorkflowRunUpdateEvent event = event("claim");
        setRows(row(new WorkflowRunWakeupIntent(
                "intent-1",
                event,
                2,
                Instant.EPOCH,
                Instant.EPOCH.plusSeconds(1),
                "transport failed",
                null)));

        WorkflowRunWakeupIntent claimed = outbox.claimPending(25).await().indefinitely().getFirst();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Tuple> tuple = ArgumentCaptor.forClass(Tuple.class);
        verify(pgPool).preparedQuery(sql.capture());
        verify(preparedQuery).execute(tuple.capture());

        assertTrue(sql.getValue().contains("FOR UPDATE SKIP LOCKED"));
        assertTrue(sql.getValue().contains("lease_owner = $3"));
        assertTrue(sql.getValue().contains("lease_expires_at = claim_clock.now + claim_clock.lease_ttl"));
        assertTrue(sql.getValue().contains("retry_backoff"));
        assertTrue(sql.getValue().contains("last_attempt_at IS NULL"));
        assertTrue(sql.getValue().contains("last_attempt_at <= claim_clock.now - claim_clock.retry_backoff"));
        assertEquals(25, tuple.getValue().getInteger(0));
        assertEquals(30_000L, tuple.getValue().getLong(1));
        assertEquals("engine-a", tuple.getValue().getString(2));
        assertEquals(5_000L, tuple.getValue().getLong(3));
        assertEquals("intent-1", claimed.id());
        assertEquals("claim", claimed.event().reason());
        assertEquals(2, claimed.attempts());
        assertEquals("transport failed", claimed.lastError());
    }

    @Test
    void markDeliveredDeletesOnlyCurrentIntentAndMatchingEventPayload() {
        outbox.markDelivered(" intent-1 ", event("delivered")).await().indefinitely();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Tuple> tuple = ArgumentCaptor.forClass(Tuple.class);
        verify(pgPool).preparedQuery(sql.capture());
        verify(preparedQuery).execute(tuple.capture());

        assertTrue(sql.getValue().contains("DELETE FROM workflow_run_wakeup_outbox"));
        assertTrue(sql.getValue().contains("intent_id = $1"));
        assertTrue(sql.getValue().contains("lease_owner = $2"));
        assertTrue(sql.getValue().contains("event_payload = $3::jsonb"));
        assertEquals("intent-1", tuple.getValue().getString(0));
        assertEquals("engine-a", tuple.getValue().getString(1));
        assertTrue(tuple.getValue().getString(2).contains("\"reason\":\"delivered\""));
    }

    @Test
    void markFailedUpdatesAttemptMetadataAndDeadLettersWhenBudgetExceeded() {
        outbox.markFailed(" intent-1 ", new IllegalStateException("event bus down")).await().indefinitely();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Tuple> tuple = ArgumentCaptor.forClass(Tuple.class);
        verify(pgPool).preparedQuery(sql.capture());
        verify(preparedQuery).execute(tuple.capture());

        assertTrue(sql.getValue().contains("WITH failed AS"));
        assertTrue(sql.getValue().contains("UPDATE workflow_run_wakeup_outbox"));
        assertTrue(sql.getValue().contains("attempts = attempts + 1"));
        assertTrue(sql.getValue().contains("lease_owner = NULL"));
        assertTrue(sql.getValue().contains("lease_expires_at = NULL"));
        assertTrue(sql.getValue().contains("WHERE intent_id = $1"));
        assertTrue(sql.getValue().contains("lease_owner = $3"));
        assertTrue(sql.getValue().contains("INSERT INTO workflow_run_wakeup_dead_letters"));
        assertTrue(sql.getValue().contains("WHERE attempts >= $5::int"));
        assertTrue(sql.getValue().contains("DELETE FROM workflow_run_wakeup_outbox"));
        assertEquals("intent-1", tuple.getValue().getString(0));
        assertTrue(tuple.getValue().getString(1).contains("event bus down"));
        assertEquals("engine-a", tuple.getValue().getString(2));
        assertEquals(WorkflowRunWakeupDeadLetterReasons.MAX_DELIVERY_ATTEMPTS_EXCEEDED,
                tuple.getValue().getString(3));
        assertEquals(100, tuple.getValue().getInteger(4));
    }

    @Test
    void deadLettersDeserializesQuarantinedWakeupsInDeadLetterOrder() throws Exception {
        WorkflowRunWakeupDeadLetter deadLetter = new WorkflowRunWakeupDeadLetter(
                "intent-1",
                event("dead"),
                WorkflowRunWakeupDeadLetterReasons.MAX_DELIVERY_ATTEMPTS_EXCEEDED,
                100,
                Instant.EPOCH,
                Instant.EPOCH.plusSeconds(10),
                "transport failed",
                Instant.EPOCH.plusSeconds(10));
        setRows(row(deadLetter));

        WorkflowRunWakeupDeadLetter restored = outbox.deadLetters(10).await().indefinitely().getFirst();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Tuple> tuple = ArgumentCaptor.forClass(Tuple.class);
        verify(pgPool).preparedQuery(sql.capture());
        verify(preparedQuery).execute(tuple.capture());

        assertTrue(sql.getValue().contains("FROM workflow_run_wakeup_dead_letters"));
        assertTrue(sql.getValue().contains("ORDER BY dead_lettered_at DESC"));
        assertEquals(10, tuple.getValue().getInteger(0));
        assertEquals("intent-1", restored.intentId());
        assertEquals("dead", restored.event().reason());
        assertEquals(WorkflowRunWakeupDeadLetterReasons.MAX_DELIVERY_ATTEMPTS_EXCEEDED,
                restored.deadLetterReason());
        assertEquals(100, restored.attempts());
        assertEquals("transport failed", restored.lastError());
    }

    @Test
    void deadLettersAppliesFiltersToSql() {
        assertTrue(outbox.deadLetters(new DeadLetterQuery(
                25,
                "run-1",
                "tenant-1",
                "retry-failed",
                WorkflowRunWakeupDeadLetterReasons.MAX_DELIVERY_ATTEMPTS_EXCEEDED))
                .await()
                .indefinitely()
                .isEmpty());

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Tuple> tuple = ArgumentCaptor.forClass(Tuple.class);
        verify(pgPool).preparedQuery(sql.capture());
        verify(preparedQuery).execute(tuple.capture());

        assertTrue(sql.getValue().contains("WHERE run_id = $1 AND tenant_id = $2"));
        assertTrue(sql.getValue().contains("reason = $3"));
        assertTrue(sql.getValue().contains("dead_letter_reason = $4"));
        assertTrue(sql.getValue().contains("LIMIT $5::int"));
        assertEquals("run-1", tuple.getValue().getString(0));
        assertEquals("tenant-1", tuple.getValue().getString(1));
        assertEquals("retry-failed", tuple.getValue().getString(2));
        assertEquals(WorkflowRunWakeupDeadLetterReasons.MAX_DELIVERY_ATTEMPTS_EXCEEDED,
                tuple.getValue().getString(3));
        assertEquals(25, tuple.getValue().getInteger(4));
    }

    @Test
    void deadLetterCountReadsDeadLetterTableCount() {
        setRows(countRow(3L));

        long count = outbox.deadLetterCount().await().indefinitely();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(pgPool).preparedQuery(sql.capture());

        assertTrue(sql.getValue().contains("SELECT COUNT(*)"));
        assertTrue(sql.getValue().contains("FROM workflow_run_wakeup_dead_letters"));
        assertEquals(3L, count);
    }

    @Test
    void deadLetterCountAppliesFiltersToSql() {
        setRows(countRow(2L));

        long count = outbox.deadLetterCount(new DeadLetterQuery(
                100,
                "run-1",
                "tenant-1",
                null,
                WorkflowRunWakeupDeadLetterReasons.MAX_DELIVERY_ATTEMPTS_EXCEEDED))
                .await()
                .indefinitely();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Tuple> tuple = ArgumentCaptor.forClass(Tuple.class);
        verify(pgPool).preparedQuery(sql.capture());
        verify(preparedQuery).execute(tuple.capture());

        assertTrue(sql.getValue().contains("WHERE run_id = $1 AND tenant_id = $2"));
        assertTrue(sql.getValue().contains("dead_letter_reason = $3"));
        assertEquals("run-1", tuple.getValue().getString(0));
        assertEquals("tenant-1", tuple.getValue().getString(1));
        assertEquals(WorkflowRunWakeupDeadLetterReasons.MAX_DELIVERY_ATTEMPTS_EXCEEDED,
                tuple.getValue().getString(2));
        assertEquals(2L, count);
    }

    @Test
    void replayDeadLetterRequeuesFreshWakeupAndDeletesDeadLetterAfterUpsert() throws Exception {
        WorkflowRunUpdateEvent event = event("replay");
        setRows(row(new WorkflowRunWakeupIntent(
                "replayed-intent",
                event,
                0,
                Instant.EPOCH,
                null,
                null,
                null)));

        WorkflowRunWakeupIntent replayed = outbox.replayDeadLetter(" intent-1 ")
                .await().indefinitely()
                .orElseThrow();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Tuple> tuple = ArgumentCaptor.forClass(Tuple.class);
        verify(pgPool).preparedQuery(sql.capture());
        verify(preparedQuery).execute(tuple.capture());

        assertTrue(sql.getValue().contains("FROM workflow_run_wakeup_dead_letters"));
        assertTrue(sql.getValue().contains("INSERT INTO workflow_run_wakeup_outbox"));
        assertTrue(sql.getValue().contains("attempts = 0"));
        assertTrue(sql.getValue().contains("last_attempt_at = NULL"));
        assertTrue(sql.getValue().contains("DELETE FROM workflow_run_wakeup_dead_letters"));
        assertTrue(sql.getValue().contains("EXISTS (SELECT 1 FROM upserted)"));
        assertEquals("intent-1", tuple.getValue().getString(0));
        assertNotEquals("intent-1", tuple.getValue().getString(1));
        assertEquals(10_000, tuple.getValue().getInteger(2));
        assertEquals("replayed-intent", replayed.id());
        assertEquals("replay", replayed.event().reason());
        assertEquals(0, replayed.attempts());
    }

    @Test
    void replayDeadLetterReturnsEmptyWhenDeadLetterDoesNotExist() {
        assertTrue(outbox.replayDeadLetter("missing").await().indefinitely().isEmpty());
    }

    @Test
    void deleteDeadLetterDeletesByIntentId() {
        setRows(countRow(1L));

        assertTrue(outbox.deleteDeadLetter(" intent-1 ").await().indefinitely());

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Tuple> tuple = ArgumentCaptor.forClass(Tuple.class);
        verify(pgPool).preparedQuery(sql.capture());
        verify(preparedQuery).execute(tuple.capture());

        assertTrue(sql.getValue().contains("DELETE FROM workflow_run_wakeup_dead_letters"));
        assertTrue(sql.getValue().contains("WHERE intent_id = $1"));
        assertEquals("intent-1", tuple.getValue().getString(0));
    }

    @Test
    void purgeDeadLettersBuildsRetentionSqlAndDryRunResult() {
        setRows(purgeRow("intent-1", false), purgeRow("intent-2", false));

        DeadLetterPurgeResult result = outbox.purgeDeadLetters(new DeadLetterPurgePolicy(
                new DeadLetterQuery(100, "run-1", "tenant-1", null, null),
                Duration.ofHours(1),
                10,
                true))
                .await()
                .indefinitely();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Tuple> tuple = ArgumentCaptor.forClass(Tuple.class);
        verify(pgPool).preparedQuery(sql.capture());
        verify(preparedQuery).execute(tuple.capture());

        assertTrue(sql.getValue().contains("WITH matched AS"));
        assertTrue(sql.getValue().contains("WHERE run_id = $1 AND tenant_id = $2"));
        assertTrue(sql.getValue().contains("OFFSET $3::int"));
        assertTrue(sql.getValue().contains("dead_lettered_at < $4::timestamptz"));
        assertTrue(sql.getValue().contains("AND NOT $5::boolean"));
        assertEquals("run-1", tuple.getValue().getString(0));
        assertEquals("tenant-1", tuple.getValue().getString(1));
        assertEquals(10, tuple.getValue().getInteger(2));
        assertTrue(tuple.getValue().getString(3).endsWith("Z"));
        assertEquals(true, tuple.getValue().getBoolean(4));
        assertEquals(2, result.selected());
        assertEquals(0, result.purged());
        assertEquals(true, result.dryRun());
        assertEquals(List.of("intent-1", "intent-2"), result.intentIds());
    }

    @Test
    void purgeDeadLettersDeletesWhenDryRunIsFalse() {
        setRows(purgeRow("intent-1", true));

        DeadLetterPurgeResult result = outbox.purgeDeadLetters(new DeadLetterPurgePolicy(
                DeadLetterQuery.all(),
                null,
                0,
                false))
                .await()
                .indefinitely();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Tuple> tuple = ArgumentCaptor.forClass(Tuple.class);
        verify(pgPool).preparedQuery(sql.capture());
        verify(preparedQuery).execute(tuple.capture());

        assertTrue(sql.getValue().contains("DELETE FROM workflow_run_wakeup_dead_letters"));
        assertTrue(sql.getValue().contains("OFFSET $1::int"));
        assertTrue(sql.getValue().contains("AND NOT $2::boolean"));
        assertEquals(0, tuple.getValue().getInteger(0));
        assertEquals(false, tuple.getValue().getBoolean(1));
        assertEquals(1, result.selected());
        assertEquals(1, result.purged());
        assertEquals(false, result.dryRun());
        assertEquals(List.of("intent-1"), result.intentIds());
    }

    @Test
    void purgeDeadLettersWithoutRetentionCriteriaDoesNotQueryDatabase() {
        DeadLetterPurgeResult result = outbox.purgeDeadLetters(DeadLetterPurgePolicy.disabled())
                .await()
                .indefinitely();

        verify(pgPool, never()).preparedQuery(anyString());
        assertEquals(0, result.selected());
        assertEquals(0, result.purged());
        assertEquals(true, result.dryRun());
    }

    private WorkflowRunUpdateEvent event(String reason) {
        return WorkflowRunUpdateEvent.of(
                WorkflowRunId.of("run-1"),
                TenantId.of("tenant-1"),
                reason);
    }

    @SuppressWarnings("unchecked")
    private void setRows(Row... rows) {
        RowSet<Row> rowSet = mock(RowSet.class);
        RowIterator<Row> iterator = mock(RowIterator.class);
        if (rows.length == 0) {
            when(iterator.hasNext()).thenReturn(false);
        } else if (rows.length == 1) {
            when(iterator.hasNext()).thenReturn(true, false);
            when(iterator.next()).thenReturn(rows[0]);
        } else {
            Boolean[] hasNext = new Boolean[rows.length + 1];
            java.util.Arrays.fill(hasNext, 0, rows.length, Boolean.TRUE);
            hasNext[rows.length] = Boolean.FALSE;
            when(iterator.hasNext()).thenReturn(hasNext[0], java.util.Arrays.copyOfRange(hasNext, 1, hasNext.length));
            when(iterator.next()).thenReturn(rows[0], java.util.Arrays.copyOfRange(rows, 1, rows.length));
        }
        when(rowSet.iterator()).thenReturn(iterator);
        when(preparedQuery.execute(any(Tuple.class))).thenReturn(Uni.createFrom().item(rowSet));
    }

    private Row row(WorkflowRunWakeupIntent intent) throws Exception {
        Row row = mock(Row.class);
        when(row.getString("intent_id")).thenReturn(intent.id());
        when(row.getValue("event_payload")).thenReturn(objectMapper.writeValueAsString(intent.event()));
        when(row.getInteger("attempts")).thenReturn(intent.attempts());
        when(row.getValue("created_at")).thenReturn(intent.createdAt());
        when(row.getValue("last_attempt_at")).thenReturn(intent.lastAttemptAt());
        when(row.getString("last_error")).thenReturn(intent.lastError());
        return row;
    }

    private Row row(WorkflowRunWakeupDeadLetter deadLetter) throws Exception {
        Row row = mock(Row.class);
        when(row.getString("intent_id")).thenReturn(deadLetter.intentId());
        when(row.getValue("event_payload")).thenReturn(objectMapper.writeValueAsString(deadLetter.event()));
        when(row.getString("dead_letter_reason")).thenReturn(deadLetter.deadLetterReason());
        when(row.getInteger("attempts")).thenReturn(deadLetter.attempts());
        when(row.getValue("created_at")).thenReturn(deadLetter.createdAt());
        when(row.getValue("last_attempt_at")).thenReturn(deadLetter.lastAttemptAt());
        when(row.getString("last_error")).thenReturn(deadLetter.lastError());
        when(row.getValue("dead_lettered_at")).thenReturn(deadLetter.deadLetteredAt());
        return row;
    }

    private Row countRow(long count) {
        Row row = mock(Row.class);
        when(row.getLong(0)).thenReturn(count);
        return row;
    }

    private Row purgeRow(String intentId, boolean purged) {
        Row row = mock(Row.class);
        when(row.getString("intent_id")).thenReturn(intentId);
        when(row.getBoolean("purged")).thenReturn(purged);
        return row;
    }
}
