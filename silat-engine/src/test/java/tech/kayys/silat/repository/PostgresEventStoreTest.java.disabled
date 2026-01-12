package tech.kayys.silat.repository;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.InjectMock;
import io.vertx.mutiny.pgclient.PgPool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.RowSet;
import io.vertx.mutiny.sqlclient.RowIterator;
import io.vertx.mutiny.sqlclient.PreparedQuery;
import io.vertx.mutiny.sqlclient.Tuple;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tech.kayys.silat.model.WorkflowRunId;
import tech.kayys.silat.model.event.ExecutionEvent;
import tech.kayys.silat.model.event.WorkflowStartedEvent;
import tech.kayys.silat.model.TenantId;
import tech.kayys.silat.model.WorkflowDefinitionId;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@QuarkusTest
public class PostgresEventStoreTest {

    @Inject
    PostgresEventStore eventStore;

    @InjectMock
    PgPool pgPool;

    @Inject
    com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @SuppressWarnings("rawtypes")
    private PreparedQuery preparedQuery;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        preparedQuery = mock(PreparedQuery.class);
        when(pgPool.preparedQuery(anyString())).thenReturn(preparedQuery);

        RowSet<Row> emptyRowSet = mock(RowSet.class);
        RowIterator<Row> emptyIterator = mock(RowIterator.class);
        when(emptyRowSet.iterator()).thenReturn(emptyIterator);
        when(emptyIterator.hasNext()).thenReturn(false);
        when(preparedQuery.execute(any())).thenReturn(Uni.createFrom().item(emptyRowSet));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testAppendEvents() {
        WorkflowRunId runId = WorkflowRunId.of(UUID.randomUUID().toString());
        ExecutionEvent event = new WorkflowStartedEvent(
                UUID.randomUUID().toString(),
                runId,
                WorkflowDefinitionId.of("def-1"),
                TenantId.of("test-tenant"),
                Map.of(),
                Instant.now());

        when(preparedQuery.execute(any(Tuple.class))).thenReturn(Uni.createFrom().item(mock(RowSet.class)));

        eventStore.appendEvents(runId, List.of(event), 0).await().indefinitely();

        verify(pgPool).preparedQuery(contains("INSERT INTO workflow_events"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testGetEvents() {
        WorkflowRunId runId = WorkflowRunId.of(UUID.randomUUID().toString());

        eventStore.getEvents(runId).await().indefinitely();

        verify(pgPool).preparedQuery(contains("SELECT event_id"));
    }
}
