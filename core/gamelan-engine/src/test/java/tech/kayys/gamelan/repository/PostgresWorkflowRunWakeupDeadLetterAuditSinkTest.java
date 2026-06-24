package tech.kayys.gamelan.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
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
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupDeadLetterAuditEvent;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupDeadLetterAuditEvent.Operation;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupDeadLetterAuditEvent.Outcome;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupDeadLetterAuditSink.AuditPurgePolicy;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupDeadLetterAuditSink.AuditPurgeResult;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupDeadLetterAuditSink.AuditQuery;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupDeadLetterAuditSink.AuditSummary;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupOutbox.DeadLetterQuery;

class PostgresWorkflowRunWakeupDeadLetterAuditSinkTest {

    private Pool pgPool;

    @SuppressWarnings("rawtypes")
    private PreparedQuery preparedQuery;

    private PostgresWorkflowRunWakeupDeadLetterAuditSink sink;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        pgPool = mock(Pool.class);
        preparedQuery = mock(PreparedQuery.class);
        RowSet<Row> rows = mock(RowSet.class);
        when(pgPool.preparedQuery(anyString())).thenReturn(preparedQuery);
        when(preparedQuery.execute(any(Tuple.class))).thenReturn(Uni.createFrom().item(rows));

        sink = new PostgresWorkflowRunWakeupDeadLetterAuditSink();
        sink.pgPool = pgPool;
        sink.objectMapper = new ObjectMapper().findAndRegisterModules();
    }

    @Test
    void appendInsertsNormalizedAuditRecord() {
        WorkflowRunWakeupDeadLetterAuditEvent event = WorkflowRunWakeupDeadLetterAuditEvent.bulk(
                Operation.BULK_DELETE,
                new DeadLetterQuery(100, "run-1", "tenant-1", "retry", null),
                2,
                1,
                1,
                0,
                false,
                List.of("intent-1", "intent-2"),
                "delete failed");

        sink.append(event).await().indefinitely();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Tuple> tuple = ArgumentCaptor.forClass(Tuple.class);
        verify(pgPool).preparedQuery(sql.capture());
        verify(preparedQuery).execute(tuple.capture());

        assertTrue(sql.getValue().contains("INSERT INTO workflow_run_wakeup_dead_letter_audit"));
        assertTrue(sql.getValue().contains("query_payload"));
        assertTrue(sql.getValue().contains("intent_ids"));
        assertEquals(Operation.BULK_DELETE.name(), tuple.getValue().getString(1));
        assertEquals(event.outcome().name(), tuple.getValue().getString(2));
        assertEquals(null, tuple.getValue().getString(3));
        assertTrue(tuple.getValue().getString(4).contains("\"runId\":\"run-1\""));
        assertEquals(2, tuple.getValue().getInteger(5));
        assertEquals(1, tuple.getValue().getInteger(6));
        assertEquals(1, tuple.getValue().getInteger(7));
        assertEquals(0, tuple.getValue().getInteger(8));
        assertEquals(false, tuple.getValue().getBoolean(9));
        assertTrue(tuple.getValue().getString(10).contains("intent-1"));
        assertEquals("delete failed", tuple.getValue().getString(11));
        assertEquals(event.occurredAt().toString(), tuple.getValue().getString(12));
    }

    @Test
    void entriesAppliesFiltersToSqlAndRestoresAuditEvents() throws Exception {
        WorkflowRunWakeupDeadLetterAuditEvent stored = new WorkflowRunWakeupDeadLetterAuditEvent(
                Operation.BULK_REPLAY,
                Outcome.SUCCEEDED,
                null,
                new DeadLetterQuery(100, "run-1", "tenant-1", null, null),
                2,
                2,
                0,
                0,
                false,
                List.of("intent-1", "intent-2"),
                null,
                Instant.parse("2026-06-08T00:00:00Z"));
        setRows(row(stored));

        List<WorkflowRunWakeupDeadLetterAuditEvent> entries = sink.entries(new AuditQuery(
                25,
                Operation.BULK_REPLAY,
                Outcome.SUCCEEDED,
                "intent-1",
                "run-1",
                "tenant-1",
                false,
                Instant.parse("2026-06-07T00:00:00Z"),
                Instant.parse("2026-06-09T00:00:00Z")))
                .await()
                .indefinitely();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Tuple> tuple = ArgumentCaptor.forClass(Tuple.class);
        verify(pgPool).preparedQuery(sql.capture());
        verify(preparedQuery).execute(tuple.capture());

        assertTrue(sql.getValue().contains("WHERE operation = $1 AND outcome = $2"));
        assertTrue(sql.getValue().contains("(intent_id = $3 OR intent_ids ? $3)"));
        assertTrue(sql.getValue().contains("query_payload ->> 'runId' = $4"));
        assertTrue(sql.getValue().contains("query_payload ->> 'tenantId' = $5"));
        assertTrue(sql.getValue().contains("dry_run = $6"));
        assertTrue(sql.getValue().contains("occurred_at >= $7::timestamptz"));
        assertTrue(sql.getValue().contains("occurred_at <= $8::timestamptz"));
        assertTrue(sql.getValue().contains("LIMIT $9::int"));
        assertEquals(Operation.BULK_REPLAY.name(), tuple.getValue().getString(0));
        assertEquals(Outcome.SUCCEEDED.name(), tuple.getValue().getString(1));
        assertEquals("intent-1", tuple.getValue().getString(2));
        assertEquals("run-1", tuple.getValue().getString(3));
        assertEquals("tenant-1", tuple.getValue().getString(4));
        assertEquals(false, tuple.getValue().getBoolean(5));
        assertEquals("2026-06-07T00:00:00Z", tuple.getValue().getString(6));
        assertEquals("2026-06-09T00:00:00Z", tuple.getValue().getString(7));
        assertEquals(25, tuple.getValue().getInteger(8));
        assertEquals(1, entries.size());
        assertEquals(stored.operation(), entries.getFirst().operation());
        assertEquals("run-1", entries.getFirst().query().runId());
        assertEquals(List.of("intent-1", "intent-2"), entries.getFirst().intentIds());
    }

    @Test
    void countAppliesFiltersToSql() {
        setRows(countRow(3L));

        long count = sink.count(new AuditQuery(
                100,
                Operation.PURGE,
                null,
                null,
                "run-1",
                null,
                true,
                null,
                null))
                .await()
                .indefinitely();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Tuple> tuple = ArgumentCaptor.forClass(Tuple.class);
        verify(pgPool).preparedQuery(sql.capture());
        verify(preparedQuery).execute(tuple.capture());

        assertTrue(sql.getValue().contains("SELECT COUNT(*)"));
        assertTrue(sql.getValue().contains("WHERE operation = $1"));
        assertTrue(sql.getValue().contains("query_payload ->> 'runId' = $2"));
        assertTrue(sql.getValue().contains("dry_run = $3"));
        assertEquals(Operation.PURGE.name(), tuple.getValue().getString(0));
        assertEquals("run-1", tuple.getValue().getString(1));
        assertEquals(true, tuple.getValue().getBoolean(2));
        assertEquals(3L, count);
    }

    @Test
    void summaryAggregatesTotalsAndBucketsWithSqlFilters() {
        setRowSets(
                rowSet(summaryTotalsRow(3L, 5L, 4L, 1L, 0L)),
                rowSet(bucketRow(Operation.BULK_REPLAY, Outcome.SUCCEEDED, false, 2L),
                        bucketRow(Operation.PURGE, Outcome.DRY_RUN, true, 1L)));

        AuditSummary summary = sink.summary(new AuditQuery(
                100,
                null,
                null,
                null,
                "run-1",
                "tenant-1",
                null,
                null,
                null))
                .await()
                .indefinitely();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Tuple> tuple = ArgumentCaptor.forClass(Tuple.class);
        verify(pgPool, times(2)).preparedQuery(sql.capture());
        verify(preparedQuery, times(2)).execute(tuple.capture());

        assertTrue(sql.getAllValues().get(0).contains("COALESCE(SUM(selected_count), 0)::bigint"));
        assertTrue(sql.getAllValues().get(0).contains("query_payload ->> 'runId' = $1"));
        assertTrue(sql.getAllValues().get(1).contains("GROUP BY operation, outcome, dry_run"));
        assertTrue(sql.getAllValues().get(1).contains("query_payload ->> 'tenantId' = $2"));
        assertEquals("run-1", tuple.getAllValues().get(0).getString(0));
        assertEquals("tenant-1", tuple.getAllValues().get(0).getString(1));
        assertEquals("run-1", tuple.getAllValues().get(1).getString(0));
        assertEquals("tenant-1", tuple.getAllValues().get(1).getString(1));
        assertEquals(3L, summary.totalEvents());
        assertEquals(5L, summary.selected());
        assertEquals(4L, summary.succeeded());
        assertEquals(1L, summary.failed());
        assertEquals(0L, summary.skipped());
        assertEquals(2, summary.buckets().size());
        assertEquals(Operation.BULK_REPLAY, summary.buckets().getFirst().operation());
        assertEquals(2L, summary.buckets().getFirst().events());
    }

    @Test
    void purgeBuildsRetentionSqlAndReturnsDryRunResult() {
        setRows(purgeRow("audit-1", false), purgeRow("audit-2", false));

        AuditPurgeResult result = sink.purge(new AuditPurgePolicy(
                new AuditQuery(100, Operation.PURGE, null, null, "run-1", "tenant-1", null, null, null),
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
        assertTrue(sql.getValue().contains("FROM workflow_run_wakeup_dead_letter_audit"));
        assertTrue(sql.getValue().contains("WHERE operation = $1"));
        assertTrue(sql.getValue().contains("query_payload ->> 'runId' = $2"));
        assertTrue(sql.getValue().contains("query_payload ->> 'tenantId' = $3"));
        assertTrue(sql.getValue().contains("OFFSET $4::int"));
        assertTrue(sql.getValue().contains("occurred_at < $5::timestamptz"));
        assertTrue(sql.getValue().contains("AND NOT $6::boolean"));
        assertEquals(Operation.PURGE.name(), tuple.getValue().getString(0));
        assertEquals("run-1", tuple.getValue().getString(1));
        assertEquals("tenant-1", tuple.getValue().getString(2));
        assertEquals(10, tuple.getValue().getInteger(3));
        Instant.parse(tuple.getValue().getString(4));
        assertEquals(true, tuple.getValue().getBoolean(5));
        assertEquals(2, result.selected());
        assertEquals(0, result.purged());
        assertEquals(true, result.dryRun());
        assertEquals(List.of("audit-1", "audit-2"), result.auditIds());
    }

    @Test
    void purgeAllowsActualDeleteWhenDryRunIsFalse() {
        setRows(purgeRow("audit-1", true));

        AuditPurgeResult result = sink.purge(new AuditPurgePolicy(
                AuditQuery.all(),
                null,
                0,
                false))
                .await()
                .indefinitely();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Tuple> tuple = ArgumentCaptor.forClass(Tuple.class);
        verify(pgPool).preparedQuery(sql.capture());
        verify(preparedQuery).execute(tuple.capture());

        assertTrue(sql.getValue().contains("OFFSET $1::int"));
        assertTrue(sql.getValue().contains("AND NOT $2::boolean"));
        assertEquals(0, tuple.getValue().getInteger(0));
        assertEquals(false, tuple.getValue().getBoolean(1));
        assertEquals(1, result.selected());
        assertEquals(1, result.purged());
        assertEquals(false, result.dryRun());
        assertEquals(List.of("audit-1"), result.auditIds());
    }

    @Test
    void purgeWithoutRetentionCriteriaDoesNotQueryDatabase() {
        AuditPurgeResult result = sink.purge(AuditPurgePolicy.disabled()).await().indefinitely();

        verify(pgPool, never()).preparedQuery(anyString());
        assertEquals(AuditPurgeResult.empty(true), result);
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

    @SuppressWarnings("unchecked")
    private void setRowSets(RowSet<Row> first, RowSet<Row> second) {
        when(preparedQuery.execute(any(Tuple.class)))
                .thenReturn(Uni.createFrom().item(first), Uni.createFrom().item(second));
    }

    @SuppressWarnings("unchecked")
    private RowSet<Row> rowSet(Row... rows) {
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
        return rowSet;
    }

    private Row row(WorkflowRunWakeupDeadLetterAuditEvent event) throws Exception {
        Row row = mock(Row.class);
        when(row.getString("operation")).thenReturn(event.operation().name());
        when(row.getString("outcome")).thenReturn(event.outcome().name());
        when(row.getString("intent_id")).thenReturn(event.intentId());
        when(row.getValue("query_payload")).thenReturn(sink.objectMapper.writeValueAsString(event.query()));
        when(row.getInteger("selected_count")).thenReturn(event.selected());
        when(row.getInteger("succeeded_count")).thenReturn(event.succeeded());
        when(row.getInteger("failed_count")).thenReturn(event.failed());
        when(row.getInteger("skipped_count")).thenReturn(event.skipped());
        when(row.getBoolean("dry_run")).thenReturn(event.dryRun());
        when(row.getValue("intent_ids")).thenReturn(sink.objectMapper.writeValueAsString(event.intentIds()));
        when(row.getString("error")).thenReturn(event.error());
        when(row.getValue("occurred_at")).thenReturn(event.occurredAt());
        return row;
    }

    private Row countRow(long count) {
        Row row = mock(Row.class);
        when(row.getLong(0)).thenReturn(count);
        return row;
    }

    private Row summaryTotalsRow(long totalEvents, long selected, long succeeded, long failed, long skipped) {
        Row row = mock(Row.class);
        when(row.getLong("total_events")).thenReturn(totalEvents);
        when(row.getLong("selected_count")).thenReturn(selected);
        when(row.getLong("succeeded_count")).thenReturn(succeeded);
        when(row.getLong("failed_count")).thenReturn(failed);
        when(row.getLong("skipped_count")).thenReturn(skipped);
        return row;
    }

    private Row bucketRow(Operation operation, Outcome outcome, boolean dryRun, long events) {
        Row row = mock(Row.class);
        when(row.getString("operation")).thenReturn(operation.name());
        when(row.getString("outcome")).thenReturn(outcome.name());
        when(row.getBoolean("dry_run")).thenReturn(dryRun);
        when(row.getLong("events")).thenReturn(events);
        return row;
    }

    private Row purgeRow(String auditId, boolean purged) {
        Row row = mock(Row.class);
        when(row.getString("audit_id")).thenReturn(auditId);
        when(row.getBoolean("purged")).thenReturn(purged);
        return row;
    }
}
