package tech.kayys.gamelan.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.PreparedQuery;
import io.vertx.mutiny.sqlclient.RowSet;
import io.vertx.mutiny.sqlclient.SqlConnection;
import io.vertx.mutiny.sqlclient.Tuple;
import tech.kayys.gamelan.engine.event.ExecutionEvent;
import tech.kayys.gamelan.engine.event.GenericExecutionEvent;
import tech.kayys.gamelan.engine.event.NodeCompletedEvent;
import tech.kayys.gamelan.engine.execution.ExecutionHistoryAppendConflictException;
import tech.kayys.gamelan.engine.execution.ExecutionHistory;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;

class PostgresExecutionHistoryRepositoryTest {

    private Pool pgPool;
    private SqlConnection connection;

    @SuppressWarnings("rawtypes")
    private PreparedQuery preparedQuery;

    private PostgresExecutionHistoryRepository repository;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        pgPool = mock(Pool.class);
        connection = mock(SqlConnection.class);
        preparedQuery = mock(PreparedQuery.class);

        when(pgPool.preparedQuery(anyString())).thenReturn(preparedQuery);
        when(connection.preparedQuery(anyString())).thenReturn(preparedQuery);
        when(pgPool.<Void>withTransaction(any())).thenAnswer(invocation -> {
            Function<SqlConnection, Uni<Void>> work = invocation.getArgument(0);
            return work.apply(connection);
        });
        when(preparedQuery.execute(any(Tuple.class))).thenReturn(Uni.createFrom().item(rowSetWithRowCount(1)));

        repository = new PostgresExecutionHistoryRepository();
        repository.pgPool = pgPool;
        repository.objectMapper = new ObjectMapper().findAndRegisterModules();
    }

    @Test
    void append_usesWorkflowEventStoreWithAdvisorySequencing() {
        repository.append(
                WorkflowRunId.of("run-1"),
                TenantId.of("tenant-1"),
                "SIGNAL_RECEIVED",
                "External signal received",
                Map.of("idempotencyKey", "sig-1"));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(pgPool).preparedQuery(sql.capture());

        String statement = sql.getValue();
        assertTrue(statement.contains("workflow_events"));
        assertTrue(statement.contains("pg_advisory_xact_lock"));
        assertTrue(statement.contains("hashtext($3 || ':' || $2)"));
        assertTrue(statement.contains("sequence_number"));
        assertTrue(statement.contains("workflow_events.tenant_id = $3"));
        assertTrue(statement.contains("ON CONFLICT (event_id) DO NOTHING"));
        assertTrue(statement.contains("RETURNING 1"));
    }

    @Test
    void appendEvents_serializesDomainEventPayloadForAuditReplay() {
        repository.appendEvents(
                WorkflowRunId.of("run-1"),
                TenantId.of("tenant-1"),
                List.of(new NodeCompletedEvent(
                        "event-1",
                        WorkflowRunId.of("run-1"),
                        NodeId.of("node-1"),
                        2,
                        Map.of("answer", "ok"),
                        Instant.EPOCH)))
                .await()
                .indefinitely();

        ArgumentCaptor<Tuple> tuple = ArgumentCaptor.forClass(Tuple.class);
        verify(preparedQuery).execute(tuple.capture());
        verify(pgPool).withTransaction(any());
        verify(connection).preparedQuery(anyString());

        String eventDataJson = tuple.getValue().getString(4);
        String metadataJson = tuple.getValue().getString(6);
        assertTrue(eventDataJson.contains("\"output\""));
        assertTrue(eventDataJson.contains("\"answer\":\"ok\""));
        assertTrue(metadataJson.contains("\"domainEventType\":\"NodeCompleted\""));
    }

    @Test
    void appendEvents_appendsBatchInSingleTransaction() {
        WorkflowRunId runId = WorkflowRunId.of("run-1");

        repository.appendEvents(
                runId,
                TenantId.of("tenant-1"),
                List.of(
                        new NodeCompletedEvent(
                                "event-1",
                                runId,
                                NodeId.of("node-1"),
                                1,
                                Map.of(),
                                Instant.EPOCH),
                        new NodeCompletedEvent(
                                "event-2",
                                runId,
                                NodeId.of("node-2"),
                                1,
                                Map.of(),
                                Instant.EPOCH)))
                .await()
                .indefinitely();

        verify(pgPool).withTransaction(any());
        verify(connection, times(2)).preparedQuery(anyString());
        verify(preparedQuery, times(2)).execute(any(Tuple.class));
    }

    @Test
    void appendEvents_failsWhenEventIdConflictDropsRow() {
        when(preparedQuery.execute(any(Tuple.class))).thenReturn(Uni.createFrom().item(rowSetWithRowCount(0)));
        WorkflowRunId runId = WorkflowRunId.of("run-1");

        ExecutionHistoryAppendConflictException error = assertThrows(ExecutionHistoryAppendConflictException.class,
                () -> repository.appendEvents(
                        runId,
                        TenantId.of("tenant-1"),
                        List.of(new NodeCompletedEvent(
                                "duplicate-event",
                                runId,
                                NodeId.of("node-1"),
                                1,
                                Map.of(),
                                Instant.EPOCH)))
                        .await()
                        .indefinitely());

        assertEquals("duplicate-event", error.eventId());
        assertEquals(runId, error.runId());
        assertEquals(TenantId.of("tenant-1"), error.tenantId().orElseThrow());
    }

    @Test
    void appendEvents_preparesPayloadBeforeOpeningTransaction() {
        repository.objectMapper = new ObjectMapper() {
            @Override
            public <T> T convertValue(Object fromValue, com.fasterxml.jackson.core.type.TypeReference<T> toValueTypeRef)
                    throws IllegalArgumentException {
                throw new IllegalArgumentException("bad payload");
            }
        };

        assertThrows(IllegalArgumentException.class, () -> repository.appendEvents(
                WorkflowRunId.of("run-1"),
                TenantId.of("tenant-1"),
                List.<ExecutionEvent>of(new NodeCompletedEvent(
                        "event-1",
                        WorkflowRunId.of("run-1"),
                        NodeId.of("node-1"),
                        1,
                        Map.of(),
                        Instant.EPOCH)))
                .await()
                .indefinitely());

        verify(pgPool, never()).withTransaction(any());
    }

    @Test
    void appendEvents_rejectsMismatchedRunBeforeOpeningTransaction() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> repository.appendEvents(
                        WorkflowRunId.of("run-1"),
                        TenantId.of("tenant-1"),
                        List.of(new GenericExecutionEvent(
                                "event-1",
                                WorkflowRunId.of("run-2"),
                                "NodeCompleted",
                                "completed",
                                Instant.EPOCH,
                                Map.of())))
                        .await()
                        .indefinitely());

        assertEquals("Execution history event run id mismatch: expected run-1 but found run-2", error.getMessage());
        verify(pgPool, never()).withTransaction(any());
    }

    @Test
    void appendEvents_rejectsTenantMismatchBeforeOpeningTransaction() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> repository.appendEvents(
                        WorkflowRunId.of("run-1"),
                        TenantId.of("tenant-1"),
                        List.of(new GenericExecutionEvent(
                                "event-1",
                                WorkflowRunId.of("run-1"),
                                "NodeCompleted",
                                "completed",
                                Instant.EPOCH,
                                Map.of("tenantId", "tenant-2"))))
                        .await()
                        .indefinitely());

        assertEquals("Execution history event tenant id mismatch: expected tenant-1 but found tenant-2",
                error.getMessage());
        verify(pgPool, never()).withTransaction(any());
    }

    @Test
    void load_readsOnlyLegacySystemHistoryForGenericLookup() {
        repository.load(WorkflowRunId.of("run-1"));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(pgPool).preparedQuery(sql.capture());

        assertTrue(sql.getValue().contains("tenant_id = 'system'"));
        assertTrue(sql.getValue().contains("ORDER BY sequence_number ASC"));
    }

    @Test
    void loadWithTenant_usesTenantStreamOrFallsBackToSystemWithoutMerging() {
        repository.load(WorkflowRunId.of("run-1"), TenantId.of("tenant-1"));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(pgPool).preparedQuery(sql.capture());

        String statement = sql.getValue();
        assertTrue(statement.contains("WITH tenant_stream AS"));
        assertTrue(statement.contains("tenant_id = $2"));
        assertTrue(statement.contains("tenant_id = 'system' AND NOT tenant_stream.has_tenant_events"));
        assertTrue(statement.contains("ORDER BY sequence_number ASC"));
    }

    @Test
    void loadWithTenant_copiesRowTenantIntoGenericEventMetadata() {
        when(preparedQuery.execute(any(Tuple.class))).thenReturn(Uni.createFrom().item(rowSetWithRows(row(
                "event-1",
                "tenant-1",
                "NodeCompleted",
                Map.of(
                        "message", "Node completed",
                        "definitionId", "wf-2",
                        "workflowVersion", "2.1.0",
                        "nodeId", "node-1",
                        "attempt", 2,
                        "output", Map.of("answer", "ok")),
                Map.of("source", "domain-event"),
                Instant.EPOCH))));

        ExecutionHistory history = repository.load(WorkflowRunId.of("run-1"), TenantId.of("tenant-1"))
                .await()
                .indefinitely();

        assertEquals(1, history.getEvents().size());
        assertEquals("tenant-1", history.getEvents().getFirst().getMetadata().get("tenantId"));
        assertEquals("node-1", history.getEvents().getFirst().getMetadata().get("nodeId"));
        assertEquals("ok", history.getEvents().getFirst().getPayload().get("answer"));
        assertEquals(WorkflowId.of("wf-2"), history.getWorkflowId());
        assertEquals("2.1.0", history.getWorkflowVersion());
    }

    @Test
    void load_failsWhenStoredEventDataCannotBeParsed() {
        when(preparedQuery.execute(any(Tuple.class))).thenReturn(Uni.createFrom().item(rowSetWithRows(row(
                "bad-event",
                "tenant-1",
                "NodeCompleted",
                "{not-json",
                Map.of("source", "domain-event"),
                Instant.EPOCH))));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> repository.load(WorkflowRunId.of("run-1"), TenantId.of("tenant-1"))
                        .await()
                        .indefinitely());

        assertTrue(error.getMessage().contains("bad-event"));
        assertTrue(error.getMessage().contains("NodeCompleted"));
        assertTrue(error.getMessage().contains("run-1"));
    }

    @Test
    void load_failsWhenStoredMetadataCannotBeParsed() {
        when(preparedQuery.execute(any(Tuple.class))).thenReturn(Uni.createFrom().item(rowSetWithRows(row(
                "bad-metadata-event",
                "tenant-1",
                "NodeCompleted",
                Map.of("message", "Node completed"),
                "{not-json",
                Instant.EPOCH))));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> repository.load(WorkflowRunId.of("run-1"), TenantId.of("tenant-1"))
                        .await()
                        .indefinitely());

        assertTrue(error.getMessage().contains("bad-metadata-event"));
        assertTrue(error.getMessage().contains("NodeCompleted"));
        assertTrue(error.getCause().getMessage().contains("Invalid execution history JSON payload"));
    }

    @Test
    void markExternalSignalProcessed_usesTenantScopedDurableMarker() {
        repository.markExternalSignalProcessed(
                WorkflowRunId.of("run-1"),
                TenantId.of("tenant-1"),
                "signal-hash");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(pgPool).preparedQuery(sql.capture());

        assertTrue(sql.getValue().contains("workflow_processed_external_signals"));
        assertTrue(sql.getValue().contains("WHERE NOT EXISTS"));
    }

    @Test
    void appendSignalReceivedAudit_usesDeterministicTenantScopedEventId() {
        WorkflowRunId runId = WorkflowRunId.of("run-1");
        TenantId tenantId = TenantId.of("tenant-1");

        repository.appendSignalReceivedAudit(
                runId,
                tenantId,
                "signal-hash",
                "approved",
                Map.of("idempotencyKey", "signal-hash"))
                .await()
                .indefinitely();
        repository.appendSignalReceivedAudit(
                runId,
                tenantId,
                "signal-hash",
                "approved",
                Map.of("idempotencyKey", "signal-hash"))
                .await()
                .indefinitely();

        ArgumentCaptor<Tuple> tuple = ArgumentCaptor.forClass(Tuple.class);
        verify(preparedQuery, times(2)).execute(tuple.capture());

        Tuple first = tuple.getAllValues().get(0);
        Tuple second = tuple.getAllValues().get(1);
        assertEquals(first.getString(0), second.getString(0));
        assertTrue(first.getString(0).startsWith("signal-received-"));
        assertEquals(runId.value(), first.getString(1));
        assertEquals(tenantId.value(), first.getString(2));
        assertEquals("SIGNAL_RECEIVED", first.getString(3));
        assertTrue(first.getString(4).contains("\"message\":\"approved\""));
        assertTrue(first.getString(6).contains("\"idempotencyKey\":\"signal-hash\""));
    }

    @Test
    void appendSignalReceivedAudit_returnsFalseWhenAuditAlreadyExists() {
        when(preparedQuery.execute(any(Tuple.class))).thenReturn(Uni.createFrom().item(rowSetWithRowCount(0)));

        Boolean inserted = repository.appendSignalReceivedAudit(
                WorkflowRunId.of("run-1"),
                TenantId.of("tenant-1"),
                "signal-hash",
                "approved",
                Map.of())
                .await()
                .indefinitely();

        assertFalse(inserted);
    }

    @Test
    void appendSignalIgnoredAudit_usesDistinctDeterministicEventId() {
        WorkflowRunId runId = WorkflowRunId.of("run-1");
        TenantId tenantId = TenantId.of("tenant-1");

        repository.appendSignalIgnoredAudit(
                runId,
                tenantId,
                "signal-hash",
                "Run is not accepting signals: CANCELLED",
                Map.of("idempotencyKey", "signal-hash"))
                .await()
                .indefinitely();

        ArgumentCaptor<Tuple> tuple = ArgumentCaptor.forClass(Tuple.class);
        verify(preparedQuery).execute(tuple.capture());

        Tuple inserted = tuple.getValue();
        assertTrue(inserted.getString(0).startsWith("signal-ignored-"));
        assertEquals(runId.value(), inserted.getString(1));
        assertEquals(tenantId.value(), inserted.getString(2));
        assertEquals("SIGNAL_IGNORED", inserted.getString(3));
        assertTrue(inserted.getString(4).contains("Run is not accepting signals"));
    }

    @Test
    void isNodeResultProcessed_checksTenantAndLegacyMarkers() {
        repository.isNodeResultProcessed(
                WorkflowRunId.of("run-1"),
                TenantId.of("tenant-1"),
                NodeId.of("node-1"),
                2);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(pgPool).preparedQuery(sql.capture());

        assertTrue(sql.getValue().contains("workflow_processed_node_results"));
        assertTrue(sql.getValue().contains("tenant_id IS NULL OR tenant_id = $2"));
    }

    @Test
    void markCompensationNodeProcessed_usesTenantScopedDurableMarker() {
        repository.markCompensationNodeProcessed(
                WorkflowRunId.of("run-1"),
                TenantId.of("tenant-1"),
                NodeId.of("node-1"));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(pgPool).preparedQuery(sql.capture());

        assertTrue(sql.getValue().contains("workflow_processed_compensation_nodes"));
        assertTrue(sql.getValue().contains("WHERE NOT EXISTS"));
    }

    @Test
    void isCompensationNodeProcessed_checksTenantAndLegacyMarkers() {
        repository.isCompensationNodeProcessed(
                WorkflowRunId.of("run-1"),
                TenantId.of("tenant-1"),
                NodeId.of("node-1"));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(pgPool).preparedQuery(sql.capture());

        assertTrue(sql.getValue().contains("workflow_processed_compensation_nodes"));
        assertTrue(sql.getValue().contains("tenant_id IS NULL OR tenant_id = $2"));
    }

    private static RowSet<io.vertx.mutiny.sqlclient.Row> rowSetWithRows(io.vertx.sqlclient.Row... rows) {
        return RowSet.newInstance(new TestRowSet(rows.length, List.of(rows)), io.vertx.mutiny.sqlclient.Row.__TYPE_ARG);
    }

    private static RowSet<io.vertx.mutiny.sqlclient.Row> rowSetWithRowCount(int rowCount) {
        return RowSet.newInstance(new TestRowSet(rowCount, List.of()), io.vertx.mutiny.sqlclient.Row.__TYPE_ARG);
    }

    private static io.vertx.sqlclient.Row row(
            String eventId,
            String tenantId,
            String eventType,
            Object eventData,
            Object metadata,
            Instant occurredAt) {
        Map<String, Object> columns = new LinkedHashMap<>();
        columns.put("event_id", eventId);
        columns.put("tenant_id", tenantId);
        columns.put("event_type", eventType);
        columns.put("event_data", eventData);
        columns.put("metadata", metadata);
        columns.put("occurred_at", occurredAt);
        return new MapBackedRow(columns);
    }

    private record TestRowSet(
            int rowCount,
            List<io.vertx.sqlclient.Row> rows) implements io.vertx.sqlclient.RowSet<io.vertx.sqlclient.Row> {

        @Override
        public io.vertx.sqlclient.RowIterator<io.vertx.sqlclient.Row> iterator() {
            return new TestRowIterator(rows.iterator());
        }

        @Override
        public io.vertx.sqlclient.RowSet<io.vertx.sqlclient.Row> next() {
            return null;
        }

        @Override
        public List<String> columnsNames() {
            return List.of();
        }

        @Override
        public List<io.vertx.sqlclient.desc.ColumnDescriptor> columnDescriptors() {
            return List.of();
        }

        @Override
        public int size() {
            return rows.size();
        }

        @Override
        public <V> V property(io.vertx.sqlclient.PropertyKind<V> propertyKind) {
            return null;
        }

        @Override
        public io.vertx.sqlclient.RowSet<io.vertx.sqlclient.Row> value() {
            return this;
        }
    }

    private record TestRowIterator(Iterator<io.vertx.sqlclient.Row> delegate)
            implements io.vertx.sqlclient.RowIterator<io.vertx.sqlclient.Row> {

        @Override
        public boolean hasNext() {
            return delegate.hasNext();
        }

        @Override
        public io.vertx.sqlclient.Row next() {
            return delegate.next();
        }
    }

    private static final class MapBackedRow implements io.vertx.sqlclient.Row {

        private final Map<String, Object> columns;
        private final List<String> columnNames;

        private MapBackedRow(Map<String, Object> columns) {
            this.columns = Collections.unmodifiableMap(new LinkedHashMap<>(columns));
            this.columnNames = List.copyOf(columns.keySet());
        }

        @Override
        public String getColumnName(int pos) {
            return columnNames.get(pos);
        }

        @Override
        public int getColumnIndex(String name) {
            return columnNames.indexOf(name);
        }

        @Override
        public Object getValue(int pos) {
            return columns.get(getColumnName(pos));
        }

        @Override
        public Object getValue(String name) {
            return columns.get(name);
        }

        @Override
        public io.vertx.sqlclient.Tuple addValue(Object value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int size() {
            return columns.size();
        }

        @Override
        public void clear() {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Class<?>> types() {
            List<Class<?>> types = new ArrayList<>();
            for (Object value : columns.values()) {
                types.add(value != null ? value.getClass() : Object.class);
            }
            return types;
        }
    }
}
