package tech.kayys.gamelan.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.PreparedQuery;
import io.vertx.mutiny.sqlclient.RowSet;
import io.vertx.mutiny.sqlclient.SqlConnection;
import io.vertx.mutiny.sqlclient.Tuple;
import tech.kayys.gamelan.engine.event.CompensationStartedEvent;
import tech.kayys.gamelan.engine.event.EventStreamVersionConflictException;
import tech.kayys.gamelan.engine.event.ExecutionEvent;
import tech.kayys.gamelan.engine.event.GenericExecutionEvent;
import tech.kayys.gamelan.engine.event.NodeCompletedEvent;
import tech.kayys.gamelan.engine.event.WorkflowStartedEvent;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinitionId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;

class PostgresEventStoreTest {

    private static final TenantId TENANT = TenantId.of("tenant-1");

    private Pool pgPool;
    private SqlConnection connection;

    @SuppressWarnings("rawtypes")
    private PreparedQuery preparedQuery;

    private PostgresEventStore eventStore;

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

        eventStore = new PostgresEventStore();
        eventStore.pgPool = pgPool;
        eventStore.objectMapper = new ObjectMapper().findAndRegisterModules();
    }

    @Test
    void appendEvents_usesTenantScopedLockAndExpectedVersionCheck() {
        when(preparedQuery.execute(any(Tuple.class))).thenReturn(Uni.createFrom().item(rowSetWithRowCount(1)));
        WorkflowRunId runId = WorkflowRunId.of("run-1");

        eventStore.appendEvents(runId, TENANT, List.of(new NodeCompletedEvent(
                "event-1",
                runId,
                NodeId.of("node-1"),
                1,
                Map.of("result", "ok"),
                Instant.EPOCH)), 3).await().indefinitely();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Tuple> tuple = ArgumentCaptor.forClass(Tuple.class);
        verify(pgPool).withTransaction(any());
        verify(connection).preparedQuery(sql.capture());
        verify(preparedQuery).execute(tuple.capture());

        assertTrue(sql.getValue().contains("pg_advisory_xact_lock(hashtext($3 || ':' || $2)::bigint)"));
        assertTrue(sql.getValue().contains("workflow_events.tenant_id = $3"));
        assertTrue(sql.getValue().contains("current_version.sequence_number = $8"));
        assertEquals("tenant-1", tuple.getValue().getValue(2));
        assertEquals(3L, tuple.getValue().getValue(7));
        assertEquals(4L, tuple.getValue().getValue(8));
    }

    @Test
    void appendEvents_appendsBatchInsideSingleTransactionWithSequentialVersions() {
        when(preparedQuery.execute(any(Tuple.class))).thenReturn(Uni.createFrom().item(rowSetWithRowCount(1)));
        WorkflowRunId runId = WorkflowRunId.of("run-1");

        eventStore.appendEvents(runId, TENANT, List.of(
                new NodeCompletedEvent("event-1", runId, NodeId.of("node-1"), 1, Map.of(), Instant.EPOCH),
                new NodeCompletedEvent("event-2", runId, NodeId.of("node-2"), 1, Map.of(), Instant.EPOCH)), 10)
                .await()
                .indefinitely();

        ArgumentCaptor<Tuple> tuple = ArgumentCaptor.forClass(Tuple.class);
        verify(pgPool).withTransaction(any());
        verify(connection, times(2)).preparedQuery(anyString());
        verify(preparedQuery, times(2)).execute(tuple.capture());

        assertEquals(10L, tuple.getAllValues().get(0).getValue(7));
        assertEquals(11L, tuple.getAllValues().get(0).getValue(8));
        assertEquals(11L, tuple.getAllValues().get(1).getValue(7));
        assertEquals(12L, tuple.getAllValues().get(1).getValue(8));
    }

    @Test
    void appendEvents_preparesPayloadBeforeOpeningTransaction() {
        eventStore.objectMapper = new ObjectMapper() {
            @Override
            public <T> T convertValue(Object fromValue, com.fasterxml.jackson.core.type.TypeReference<T> toValueTypeRef)
                    throws IllegalArgumentException {
                throw new IllegalArgumentException("bad payload");
            }
        };
        WorkflowRunId runId = WorkflowRunId.of("run-1");

        assertThrows(IllegalArgumentException.class, () -> eventStore.appendEvents(runId, TENANT, List.of(
                new NodeCompletedEvent("event-1", runId, NodeId.of("node-1"), 1, Map.of(), Instant.EPOCH)), 0)
                .await()
                .indefinitely());

        verify(pgPool, never()).withTransaction(any());
    }

    @Test
    void appendEvents_rejectsNullEventBeforeOpeningTransaction() {
        WorkflowRunId runId = WorkflowRunId.of("run-1");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> eventStore.appendEvents(runId, TENANT, Collections.singletonList(null), 0)
                        .await()
                        .indefinitely());

        assertEquals("Execution history contains null event", error.getMessage());
        verify(pgPool, never()).withTransaction(any());
    }

    @Test
    void appendEvents_rejectsMismatchedRunBeforeOpeningTransaction() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> eventStore.appendEvents(
                        WorkflowRunId.of("run-1"),
                        TENANT,
                        List.of(new NodeCompletedEvent(
                                "event-1",
                                WorkflowRunId.of("run-2"),
                                NodeId.of("node-1"),
                                1,
                                Map.of(),
                                Instant.EPOCH)),
                        0)
                        .await()
                        .indefinitely());

        assertEquals("Execution history event run id mismatch: expected run-1 but found run-2", error.getMessage());
        verify(pgPool, never()).withTransaction(any());
    }

    @Test
    void appendEvents_rejectsTenantMismatchBeforeOpeningTransaction() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> eventStore.appendEvents(
                        WorkflowRunId.of("run-1"),
                        TENANT,
                        List.of(new GenericExecutionEvent(
                                "event-1",
                                WorkflowRunId.of("run-1"),
                                "AgentCheckpoint",
                                "checkpoint",
                                Instant.EPOCH,
                                Map.of("tenantId", "tenant-2"))),
                        0)
                        .await()
                        .indefinitely());

        assertEquals("Execution history event tenant id mismatch: expected tenant-1 but found tenant-2",
                error.getMessage());
        verify(pgPool, never()).withTransaction(any());
    }

    @Test
    void appendEvents_infersTenantFromWorkflowStartedEvent() {
        when(preparedQuery.execute(any(Tuple.class))).thenReturn(Uni.createFrom().item(rowSetWithRowCount(1)));
        WorkflowRunId runId = WorkflowRunId.of("run-1");

        eventStore.appendEvents(runId, List.of(new WorkflowStartedEvent(
                "event-1",
                runId,
                WorkflowDefinitionId.of("wf-1"),
                TENANT,
                Map.of(),
                Instant.EPOCH)), 0).await().indefinitely();

        ArgumentCaptor<Tuple> tuple = ArgumentCaptor.forClass(Tuple.class);
        verify(preparedQuery).execute(tuple.capture());

        assertEquals("tenant-1", tuple.getValue().getValue(2));
    }

    @Test
    void appendEvents_infersTenantFromGenericMetadata() {
        when(preparedQuery.execute(any(Tuple.class))).thenReturn(Uni.createFrom().item(rowSetWithRowCount(1)));
        WorkflowRunId runId = WorkflowRunId.of("run-1");

        eventStore.appendEvents(runId, List.of(new GenericExecutionEvent(
                "event-1",
                runId,
                "RetryRequested",
                "retry node",
                Instant.EPOCH,
                Map.of("tenantId", "tenant-1"))), 0).await().indefinitely();

        ArgumentCaptor<Tuple> tuple = ArgumentCaptor.forClass(Tuple.class);
        verify(preparedQuery).execute(tuple.capture());

        assertEquals("tenant-1", tuple.getValue().getValue(2));
    }

    @Test
    void appendEvents_failsWhenExpectedVersionDoesNotMatch() {
        when(preparedQuery.execute(any(Tuple.class))).thenReturn(Uni.createFrom().item(rowSetWithRowCount(0)));
        WorkflowRunId runId = WorkflowRunId.of("run-conflict");

        EventStreamVersionConflictException error = assertThrows(EventStreamVersionConflictException.class,
                () -> eventStore.appendEvents(runId, TENANT, List.of(
                        new NodeCompletedEvent("event-1", runId, NodeId.of("node-1"), 1, Map.of(), Instant.EPOCH)), 7)
                        .await()
                        .indefinitely());

        assertEquals(runId, error.runId());
        assertEquals(TENANT, error.tenantId().orElseThrow());
        assertEquals(7L, error.expectedVersion());
    }

    @Test
    void getEvents_readsOnlySystemHistoryForLegacyLookup() {
        when(preparedQuery.execute(any(Tuple.class))).thenReturn(Uni.createFrom().item(rowSetWithRowCount(0)));

        eventStore.getEvents(WorkflowRunId.of("run-1")).await().indefinitely();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(pgPool).preparedQuery(sql.capture());

        assertTrue(sql.getValue().contains("tenant_id = 'system'"));
    }

    @Test
    void getEventsWithTenant_usesTenantStreamOrFallsBackToSystemWithoutMerging() {
        when(preparedQuery.execute(any(Tuple.class))).thenReturn(Uni.createFrom().item(rowSetWithRowCount(0)));

        eventStore.getEvents(WorkflowRunId.of("run-1"), TENANT).await().indefinitely();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Tuple> tuple = ArgumentCaptor.forClass(Tuple.class);
        verify(pgPool).preparedQuery(sql.capture());
        verify(preparedQuery).execute(tuple.capture());

        String statement = sql.getValue();
        assertTrue(statement.contains("WITH tenant_stream AS"));
        assertTrue(statement.contains("tenant_id = $2"));
        assertTrue(statement.contains("tenant_id = 'system' AND NOT tenant_stream.has_tenant_events"));
        assertTrue(statement.contains("ORDER BY sequence_number ASC"));
        assertEquals("tenant-1", tuple.getValue().getValue(1));
    }

    @Test
    void getEventsAfterVersionWithTenant_usesTenantStreamBeforeVersionFilter() {
        when(preparedQuery.execute(any(Tuple.class))).thenReturn(Uni.createFrom().item(rowSetWithRowCount(0)));

        eventStore.getEventsAfterVersion(WorkflowRunId.of("run-1"), TENANT, 7).await().indefinitely();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Tuple> tuple = ArgumentCaptor.forClass(Tuple.class);
        verify(pgPool).preparedQuery(sql.capture());
        verify(preparedQuery).execute(tuple.capture());

        String statement = sql.getValue();
        assertTrue(statement.contains("WITH tenant_stream AS"));
        assertTrue(statement.contains("tenant_id = 'system' AND NOT tenant_stream.has_tenant_events"));
        assertTrue(statement.contains("sequence_number > $3"));
        assertEquals("tenant-1", tuple.getValue().getValue(1));
        assertEquals(7L, tuple.getValue().getValue(2));
    }

    @Test
    void getEventsByTypeWithTenant_usesTenantStreamBeforeTypeFilter() {
        when(preparedQuery.execute(any(Tuple.class))).thenReturn(Uni.createFrom().item(rowSetWithRowCount(0)));

        eventStore.getEventsByType(WorkflowRunId.of("run-1"), TENANT, "NodeCompleted").await().indefinitely();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Tuple> tuple = ArgumentCaptor.forClass(Tuple.class);
        verify(pgPool).preparedQuery(sql.capture());
        verify(preparedQuery).execute(tuple.capture());

        String statement = sql.getValue();
        assertTrue(statement.contains("WITH tenant_stream AS"));
        assertTrue(statement.contains("tenant_id = 'system' AND NOT tenant_stream.has_tenant_events"));
        assertTrue(statement.contains("event_type = $3"));
        assertEquals("tenant-1", tuple.getValue().getValue(1));
        assertEquals("NodeCompleted", tuple.getValue().getValue(2));
    }

    @Test
    void getEvents_deserializesCompensationAndGenericEvents() throws Exception {
        WorkflowRunId runId = WorkflowRunId.of("run-1");
        when(preparedQuery.execute(any(Tuple.class))).thenReturn(Uni.createFrom().item(rowSetWithRows(
                row("event-1", "CompensationStarted", eventStore.objectMapper.writeValueAsString(new CompensationStartedEvent(
                        "event-1",
                        runId,
                        TENANT,
                        List.of(NodeId.of("node-1")),
                        Instant.EPOCH))),
                row("event-2", "CustomAgentSignal", eventStore.objectMapper.writeValueAsString(new GenericExecutionEvent(
                        "event-2",
                        runId,
                        "CustomAgentSignal",
                        "agent handoff requested",
                        Instant.EPOCH,
                        Map.of("agent", "planner")))))));

        List<ExecutionEvent> events = eventStore.getEvents(runId, TENANT).await().indefinitely();

        assertEquals(2, events.size());
        assertInstanceOf(CompensationStartedEvent.class, events.get(0));
        GenericExecutionEvent generic = assertInstanceOf(GenericExecutionEvent.class, events.get(1));
        assertEquals("CustomAgentSignal", generic.eventType());
    }

    @Test
    void getEvents_deserializesJsonObjectPayloadsReturnedByPostgresJsonb() throws Exception {
        WorkflowRunId runId = WorkflowRunId.of("run-1");
        NodeCompletedEvent event = new NodeCompletedEvent(
                "event-1",
                runId,
                NodeId.of("node-1"),
                2,
                Map.of("answer", "ok"),
                Instant.EPOCH);
        when(preparedQuery.execute(any(Tuple.class))).thenReturn(Uni.createFrom().item(rowSetWithRows(
                row("event-1", "NodeCompleted", new JsonObject(eventStore.objectMapper.writeValueAsString(event))))));

        List<ExecutionEvent> events = eventStore.getEvents(runId, TENANT).await().indefinitely();

        assertEquals(1, events.size());
        NodeCompletedEvent completed = assertInstanceOf(NodeCompletedEvent.class, events.getFirst());
        assertEquals(NodeId.of("node-1"), completed.nodeId());
        assertEquals("ok", completed.output().get("answer"));
    }

    @Test
    void getEvents_deserializesLegacyWorkflowStartedWithoutWorkflowVersion() {
        WorkflowRunId runId = WorkflowRunId.of("run-1");
        when(preparedQuery.execute(any(Tuple.class))).thenReturn(Uni.createFrom().item(rowSetWithRows(
                row("event-1", "WorkflowStarted", Map.of(
                        "eventId", "event-1",
                        "runId", runId.value(),
                        "definitionId", "wf-1",
                        "tenantId", TENANT.value(),
                        "inputs", Map.of(),
                        "occurredAt", Instant.EPOCH)))));

        List<ExecutionEvent> events = eventStore.getEvents(runId, TENANT).await().indefinitely();

        WorkflowStartedEvent started = assertInstanceOf(WorkflowStartedEvent.class, events.getFirst());
        assertEquals("unknown", started.workflowVersion());
    }

    @Test
    void getEvents_rejectsStoredEventForDifferentRun() throws Exception {
        WorkflowRunId runId = WorkflowRunId.of("run-1");
        NodeCompletedEvent wrongRunEvent = new NodeCompletedEvent(
                "event-1",
                WorkflowRunId.of("run-2"),
                NodeId.of("node-1"),
                1,
                Map.of(),
                Instant.EPOCH);
        when(preparedQuery.execute(any(Tuple.class))).thenReturn(Uni.createFrom().item(rowSetWithRows(
                row("event-1", "NodeCompleted", eventStore.objectMapper.writeValueAsString(wrongRunEvent)))));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> eventStore.getEvents(runId, TENANT).await().indefinitely());

        assertEquals("Execution history event run id mismatch: expected run-1 but found run-2", error.getMessage());
    }

    @Test
    void getEventsWithTenant_rejectsStoredTenantMismatch() throws Exception {
        WorkflowRunId runId = WorkflowRunId.of("run-1");
        WorkflowStartedEvent wrongTenantEvent = new WorkflowStartedEvent(
                "event-1",
                runId,
                WorkflowDefinitionId.of("wf-1"),
                TenantId.of("tenant-2"),
                Map.of(),
                Instant.EPOCH);
        when(preparedQuery.execute(any(Tuple.class))).thenReturn(Uni.createFrom().item(rowSetWithRows(
                row("event-1", "WorkflowStarted", eventStore.objectMapper.writeValueAsString(wrongTenantEvent)))));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> eventStore.getEvents(runId, TENANT).await().indefinitely());

        assertEquals("Execution history event tenant id mismatch: expected tenant-1 but found tenant-2",
                error.getMessage());
    }

    @Test
    void getEvents_failsWhenStoredEventCannotBeDeserialized() {
        when(preparedQuery.execute(any(Tuple.class))).thenReturn(Uni.createFrom().item(rowSetWithRows(
                row("bad-event", "NodeCompleted", "{not-json"))));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> eventStore.getEvents(WorkflowRunId.of("run-1"), TENANT).await().indefinitely());

        assertTrue(error.getMessage().contains("bad-event"));
        assertTrue(error.getMessage().contains("NodeCompleted"));
    }

    private static RowSet<io.vertx.mutiny.sqlclient.Row> rowSetWithRowCount(int rowCount) {
        return RowSet.newInstance(new TestRowSet(rowCount, List.of()), io.vertx.mutiny.sqlclient.Row.__TYPE_ARG);
    }

    private static RowSet<io.vertx.mutiny.sqlclient.Row> rowSetWithRows(io.vertx.sqlclient.Row... rows) {
        return RowSet.newInstance(new TestRowSet(rows.length, List.of(rows)), io.vertx.mutiny.sqlclient.Row.__TYPE_ARG);
    }

    private static io.vertx.sqlclient.Row row(String eventType, String eventData) {
        return row("event-1", eventType, eventData);
    }

    private static io.vertx.sqlclient.Row row(String eventId, String eventType, Object eventData) {
        Map<String, Object> columns = new LinkedHashMap<>();
        columns.put("event_id", eventId);
        columns.put("event_type", eventType);
        columns.put("event_data", eventData);
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
        public java.util.List<String> columnsNames() {
            return List.of();
        }

        @Override
        public java.util.List<io.vertx.sqlclient.desc.ColumnDescriptor> columnDescriptors() {
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
