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
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tech.kayys.silat.model.CallbackRegistration;
import tech.kayys.silat.model.ExecutionToken;
import tech.kayys.silat.model.NodeId;
import tech.kayys.silat.model.WorkflowRunId;
import jakarta.inject.Inject;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@QuarkusTest
public class PostgresWorkflowRunRepositoryTest {

    @Inject
    PostgresWorkflowRunRepository repository;

    @InjectMock
    PgPool pgPool;

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
    void testStoreToken() {
        ExecutionToken token = new ExecutionToken(
                "token-val",
                WorkflowRunId.of(UUID.randomUUID().toString()),
                NodeId.of("node-1"),
                1,
                Instant.now().plus(Duration.ofHours(1)));

        repository.storeToken(token).await().indefinitely();

        verify(pgPool).preparedQuery(contains("INSERT INTO execution_tokens"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testValidateToken_Success() {
        ExecutionToken token = new ExecutionToken(
                "token-val",
                WorkflowRunId.of(UUID.randomUUID().toString()),
                NodeId.of("node-1"),
                1,
                Instant.now().plus(Duration.ofHours(1)));

        RowSet<Row> rowSet = mock(RowSet.class);
        Row row = mock(Row.class);
        RowIterator<Row> iterator = mock(RowIterator.class);

        when(rowSet.iterator()).thenReturn(iterator);
        when(iterator.hasNext()).thenReturn(true);
        when(iterator.next()).thenReturn(row);
        when(row.getBoolean(0)).thenReturn(true);

        when(preparedQuery.execute(any(Tuple.class))).thenReturn(Uni.createFrom().item(rowSet));

        Boolean valid = repository.validateToken(token).await().indefinitely();

        Assertions.assertTrue(valid);
        verify(pgPool).preparedQuery(contains("SELECT EXISTS"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testStoreCallback() {
        CallbackRegistration registration = new CallbackRegistration(
                "token-val",
                WorkflowRunId.of(UUID.randomUUID().toString()),
                NodeId.of("node-1"),
                "http://callback.url",
                Instant.now().plus(Duration.ofHours(1)));

        repository.storeCallback(registration).await().indefinitely();

        verify(pgPool).preparedQuery(contains("INSERT INTO workflow_callbacks"));
    }
}
