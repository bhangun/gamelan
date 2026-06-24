package tech.kayys.gamelan.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.PreparedQuery;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.RowIterator;
import io.vertx.mutiny.sqlclient.RowSet;
import io.vertx.mutiny.sqlclient.Tuple;
import tech.kayys.gamelan.engine.repository.WorkflowRecoveryLease;

class PostgresWorkflowRecoveryLeaseRepositoryTest {

    private Pool pgPool;

    @SuppressWarnings("rawtypes")
    private PreparedQuery preparedQuery;

    private RowSet<Row> rows;
    private PostgresWorkflowRecoveryLeaseRepository repository;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        pgPool = mock(Pool.class);
        preparedQuery = mock(PreparedQuery.class);
        rows = mock(RowSet.class);
        RowIterator<Row> emptyIterator = mock(RowIterator.class);

        when(pgPool.preparedQuery(anyString())).thenReturn(preparedQuery);
        when(preparedQuery.execute(any(Tuple.class))).thenReturn(Uni.createFrom().item(rows));
        when(emptyIterator.hasNext()).thenReturn(false);
        when(rows.iterator()).thenReturn(emptyIterator);

        repository = new PostgresWorkflowRecoveryLeaseRepository();
        repository.pgPool = pgPool;
    }

    @Test
    void tryAcquireRecoveryLease_usesDatabaseClockAndReturnedTimestamps() {
        Instant databaseAcquiredAt = Instant.parse("2026-06-02T01:00:00Z");
        Instant databaseExpiresAt = databaseAcquiredAt.plusMillis(1500);
        Row row = mock(Row.class);
        when(row.getValue("acquired_at")).thenReturn(databaseAcquiredAt);
        when(row.getValue("expires_at")).thenReturn(databaseExpiresAt);
        RowIterator<Row> iterator = mock(RowIterator.class);
        when(iterator.hasNext()).thenReturn(true, false);
        when(iterator.next()).thenReturn(row);
        when(rows.iterator()).thenReturn(iterator);

        WorkflowRecoveryLease lease = repository.tryAcquireRecoveryLease(
                "workflow-recovery",
                "owner-a",
                Duration.ofMillis(1500),
                Instant.parse("2099-01-01T00:00:00Z")).await().indefinitely();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Tuple> tuple = ArgumentCaptor.forClass(Tuple.class);
        verify(pgPool).preparedQuery(sql.capture());
        verify(preparedQuery).execute(tuple.capture());

        assertTrue(sql.getValue().contains("clock_timestamp() AS acquired_at"));
        assertTrue(sql.getValue().contains("$3::bigint * INTERVAL '1 millisecond'"));
        assertTrue(sql.getValue().contains("workflow_recovery_leases.expires_at <= EXCLUDED.acquired_at"));
        assertTrue(sql.getValue().contains("RETURNING lease_name, owner_id, acquired_at, expires_at"));
        assertEquals("workflow-recovery", tuple.getValue().getString(0));
        assertEquals("owner-a", tuple.getValue().getString(1));
        assertEquals(1500L, tuple.getValue().getLong(2));
        assertTrue(lease.acquired());
        assertEquals(databaseAcquiredAt, lease.acquiredAt());
        assertEquals(databaseExpiresAt, lease.expiresAt());
    }

    @Test
    void tryAcquireRecoveryLease_returnsNotAcquiredWhenUpsertDoesNotReturnRow() {
        WorkflowRecoveryLease lease = repository.tryAcquireRecoveryLease(
                "workflow-recovery",
                "owner-b",
                Duration.ofMinutes(1),
                Instant.parse("2026-06-02T01:00:00Z")).await().indefinitely();

        assertFalse(lease.acquired());
        assertEquals("workflow-recovery", lease.leaseName());
        assertEquals("owner-b", lease.ownerId());
    }
}
