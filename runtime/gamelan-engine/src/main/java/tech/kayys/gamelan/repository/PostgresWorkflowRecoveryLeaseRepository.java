package tech.kayys.gamelan.repository;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;

import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.RowSet;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import tech.kayys.gamelan.engine.repository.WorkflowRecoveryLease;
import tech.kayys.gamelan.engine.repository.WorkflowRecoveryLeaseRepository;

@ApplicationScoped
@io.quarkus.arc.properties.IfBuildProperty(name = "quarkus.datasource.db-kind", stringValue = "postgresql")
@io.quarkus.arc.properties.IfBuildProperty(name = "gamelan.workflow.persistence.store", stringValue = "postgres", enableIfMissing = true)
public class PostgresWorkflowRecoveryLeaseRepository implements WorkflowRecoveryLeaseRepository {

    private static final Duration DEFAULT_TTL = Duration.ofMinutes(2);

    @Inject
    Pool pgPool;

    @Override
    public Uni<WorkflowRecoveryLease> tryAcquireRecoveryLease(
            String leaseName,
            String ownerId,
            Duration ttl,
            Instant now) {
        String safeLeaseName = requireText(leaseName, "leaseName");
        String safeOwnerId = requireText(ownerId, "ownerId");
        Duration safeTtl = positiveDuration(ttl, DEFAULT_TTL);
        long ttlMillis = Math.max(1L, safeTtl.toMillis());
        String sql = """
                WITH lease_clock AS (
                    SELECT clock_timestamp() AS acquired_at,
                           ($3::bigint * INTERVAL '1 millisecond') AS ttl
                )
                INSERT INTO workflow_recovery_leases
                    (lease_name, owner_id, acquired_at, expires_at, updated_at)
                SELECT $1, $2, lease_clock.acquired_at, lease_clock.acquired_at + lease_clock.ttl, lease_clock.acquired_at
                FROM lease_clock
                ON CONFLICT (lease_name) DO UPDATE
                SET owner_id = EXCLUDED.owner_id,
                    acquired_at = EXCLUDED.acquired_at,
                    expires_at = EXCLUDED.expires_at,
                    updated_at = EXCLUDED.updated_at
                WHERE workflow_recovery_leases.expires_at <= EXCLUDED.acquired_at
                   OR workflow_recovery_leases.owner_id = $2
                RETURNING lease_name, owner_id, acquired_at, expires_at
                """;

        return pgPool.preparedQuery(sql)
                .execute(Tuple.of(safeLeaseName, safeOwnerId, ttlMillis))
                .map(rows -> leaseFromRows(rows, safeLeaseName, safeOwnerId));
    }

    @Override
    public Uni<Void> releaseRecoveryLease(WorkflowRecoveryLease lease) {
        if (lease == null || !lease.acquired()) {
            return Uni.createFrom().voidItem();
        }
        String sql = """
                DELETE FROM workflow_recovery_leases
                WHERE lease_name = $1
                  AND owner_id = $2
                """;
        return pgPool.preparedQuery(sql)
                .execute(Tuple.of(lease.leaseName(), lease.ownerId()))
                .replaceWithVoid();
    }

    private static WorkflowRecoveryLease leaseFromRows(RowSet<Row> rows, String leaseName, String ownerId) {
        if (rows == null) {
            return WorkflowRecoveryLease.notAcquired(leaseName, ownerId);
        }
        java.util.Iterator<Row> iterator = rows.iterator();
        if (!iterator.hasNext()) {
            return WorkflowRecoveryLease.notAcquired(leaseName, ownerId);
        }
        Row row = iterator.next();
        return WorkflowRecoveryLease.acquired(
                leaseName,
                ownerId,
                readInstant(row, "acquired_at"),
                readInstant(row, "expires_at"));
    }

    private static Instant readInstant(Row row, String column) {
        Object value = row.getValue(column);
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof OffsetDateTime offsetDateTime) {
            return offsetDateTime.toInstant();
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toInstant();
        }
        if (value instanceof String text && !text.isBlank()) {
            return Instant.parse(text);
        }
        OffsetDateTime offsetDateTime = row.getOffsetDateTime(column);
        return offsetDateTime != null ? offsetDateTime.toInstant() : Instant.now();
    }

    private static Duration positiveDuration(Duration value, Duration fallback) {
        return value != null && !value.isZero() && !value.isNegative() ? value : fallback;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " cannot be blank");
        }
        return value.trim();
    }
}
