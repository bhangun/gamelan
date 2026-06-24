package tech.kayys.gamelan.repository;

import static tech.kayys.gamelan.engine.executor.ExecutorSelectionRejectionReasons.CAPACITY_SATURATED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

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
import tech.kayys.gamelan.engine.node.NodeExecutionTask;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.run.RetryPolicy;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;
import tech.kayys.gamelan.scheduler.TaskDeadLetterQueue;

class PostgresTaskDeadLetterQueueTest {

    private Pool pgPool;

    @SuppressWarnings("rawtypes")
    private PreparedQuery preparedQuery;

    private RowSet<Row> rows;
    private PostgresTaskDeadLetterQueue queue;
    private ObjectMapper objectMapper;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        pgPool = mock(Pool.class);
        preparedQuery = mock(PreparedQuery.class);
        rows = mock(RowSet.class);
        RowIterator<Row> emptyIterator = mock(RowIterator.class);
        objectMapper = new ObjectMapper().findAndRegisterModules();

        when(pgPool.preparedQuery(anyString())).thenReturn(preparedQuery);
        when(preparedQuery.execute(any(Tuple.class))).thenReturn(Uni.createFrom().item(rows));
        when(emptyIterator.hasNext()).thenReturn(false);
        when(rows.iterator()).thenReturn(emptyIterator);

        queue = new PostgresTaskDeadLetterQueue();
        queue.pgPool = pgPool;
        queue.objectMapper = objectMapper;
    }

    @Test
    void publishUpsertsSerializedTaskPayloadAndTenantScope() {
        queue.publish(deadLetter("message-1", CAPACITY_SATURATED)).await().indefinitely();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Tuple> tuple = ArgumentCaptor.forClass(Tuple.class);
        verify(pgPool).preparedQuery(sql.capture());
        verify(preparedQuery).execute(tuple.capture());

        assertTrue(sql.getValue().contains("INSERT INTO task_dead_letters"));
        assertTrue(sql.getValue().contains("ON CONFLICT (message_id)"));
        assertEquals("message-1", tuple.getValue().getString(0));
        assertEquals("run-1", tuple.getValue().getString(1));
        assertEquals("tenant-a", tuple.getValue().getString(2));
        assertEquals("node-1", tuple.getValue().getString(3));
        assertEquals(CAPACITY_SATURATED, tuple.getValue().getString(4));
        assertEquals(3, tuple.getValue().getInteger(5));
        assertEquals(2, tuple.getValue().getInteger(6));
        assertTrue(tuple.getValue().getString(9).contains("\"runId\""));
        assertTrue(tuple.getValue().getString(10).contains("\"selectionReason\""));
    }

    @Test
    void listBuildsFilteredQueryWithStableParameterOrder() {
        queue.list(new TaskDeadLetterQueue.DeadLetterQuery(
                25,
                "run-1",
                "node-1",
                "tenant-a",
                CAPACITY_SATURATED)).await().indefinitely();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Tuple> tuple = ArgumentCaptor.forClass(Tuple.class);
        verify(pgPool).preparedQuery(sql.capture());
        verify(preparedQuery).execute(tuple.capture());

        assertTrue(sql.getValue().contains("FROM task_dead_letters"));
        assertTrue(sql.getValue().contains("run_id = $1"));
        assertTrue(sql.getValue().contains("node_id = $2"));
        assertTrue(sql.getValue().contains("tenant_id = $3"));
        assertTrue(sql.getValue().contains("reason = $4"));
        assertTrue(sql.getValue().contains("ORDER BY dead_lettered_at DESC LIMIT $5::int"));
        assertEquals("run-1", tuple.getValue().getString(0));
        assertEquals("node-1", tuple.getValue().getString(1));
        assertEquals("tenant-a", tuple.getValue().getString(2));
        assertEquals(CAPACITY_SATURATED, tuple.getValue().getString(3));
        assertEquals(25, tuple.getValue().getInteger(4));
    }

    @Test
    void getDeserializesStoredDeadLetterTask() throws Exception {
        Row row = mock(Row.class);
        when(row.getString("message_id")).thenReturn("message-1");
        when(row.getValue("task_payload")).thenReturn(objectMapper.writeValueAsString(task()));
        when(row.getString("reason")).thenReturn(CAPACITY_SATURATED);
        when(row.getInteger("delivery_attempt")).thenReturn(3);
        when(row.getInteger("defer_count")).thenReturn(2);
        when(row.getValue("first_seen_at")).thenReturn(Instant.EPOCH);
        when(row.getValue("dead_lettered_at")).thenReturn(Instant.EPOCH.plusSeconds(60));
        when(row.getValue("diagnostics")).thenReturn("{\"selectionReason\":\"" + CAPACITY_SATURATED + "\"}");
        RowIterator<Row> rowIterator = mock(RowIterator.class);
        when(rowIterator.hasNext()).thenReturn(true, false);
        when(rowIterator.next()).thenReturn(row);
        when(rows.iterator()).thenReturn(rowIterator);

        Optional<TaskDeadLetterQueue.DeadLetterTask> deadLetter = queue.get(" message-1 ")
                .await()
                .indefinitely();

        assertTrue(deadLetter.isPresent());
        assertEquals("message-1", deadLetter.orElseThrow().messageId());
        assertEquals(WorkflowRunId.of("run-1"), deadLetter.orElseThrow().task().runId());
        assertEquals(NodeId.of("node-1"), deadLetter.orElseThrow().task().nodeId());
        assertEquals(CAPACITY_SATURATED, deadLetter.orElseThrow().diagnostics().get("selectionReason"));
    }

    @Test
    void deleteTrimsMessageIdAndReturnsFalseWhenNoRowWasDeleted() {
        boolean deleted = queue.delete(" message-1 ").await().indefinitely();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Tuple> tuple = ArgumentCaptor.forClass(Tuple.class);
        verify(pgPool).preparedQuery(sql.capture());
        verify(preparedQuery).execute(tuple.capture());

        assertTrue(sql.getValue().contains("DELETE FROM task_dead_letters"));
        assertTrue(sql.getValue().contains("RETURNING 1"));
        assertEquals("message-1", tuple.getValue().getString(0));
        assertEquals(false, deleted);
    }

    @Test
    void clearWithQueryBuildsFilteredDeleteStatement() {
        long deleted = queue.clear(new TaskDeadLetterQueue.DeadLetterQuery(
                100,
                "run-1",
                null,
                "tenant-a",
                CAPACITY_SATURATED)).await().indefinitely();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Tuple> tuple = ArgumentCaptor.forClass(Tuple.class);
        verify(pgPool).preparedQuery(sql.capture());
        verify(preparedQuery).execute(tuple.capture());

        assertTrue(sql.getValue().contains("DELETE FROM task_dead_letters"));
        assertTrue(sql.getValue().contains("run_id = $1"));
        assertTrue(sql.getValue().contains("tenant_id = $2"));
        assertTrue(sql.getValue().contains("reason = $3"));
        assertTrue(sql.getValue().contains("RETURNING 1"));
        assertEquals("run-1", tuple.getValue().getString(0));
        assertEquals("tenant-a", tuple.getValue().getString(1));
        assertEquals(CAPACITY_SATURATED, tuple.getValue().getString(2));
        assertEquals(0L, deleted);
    }

    private static TaskDeadLetterQueue.DeadLetterTask deadLetter(String messageId, String reason) {
        return new TaskDeadLetterQueue.DeadLetterTask(
                messageId,
                task(),
                reason,
                3,
                2,
                Instant.EPOCH,
                Instant.EPOCH.plusSeconds(60),
                Map.of("selectionReason", reason));
    }

    private static NodeExecutionTask task() {
        Map<String, Object> context = new HashMap<>();
        context.put(NodeExecutionTask.NODE_TYPE_KEY, "agent");
        context.put(NodeExecutionTask.TENANT_ID_KEY, "tenant-a");
        return new NodeExecutionTask(
                WorkflowRunId.of("run-1"),
                NodeId.of("node-1"),
                1,
                null,
                context,
                RetryPolicy.none());
    }
}
