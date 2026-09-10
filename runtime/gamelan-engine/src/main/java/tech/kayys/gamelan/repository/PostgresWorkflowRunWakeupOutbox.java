package tech.kayys.gamelan.repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.arc.properties.IfBuildProperty;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.RowSet;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import tech.kayys.gamelan.engine.event.WorkflowRunUpdateEvent;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupDeadLetter;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupDeadLetterReasons;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupIntent;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupOutbox;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupOutbox.DeadLetterPurgePolicy;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupOutbox.DeadLetterPurgeResult;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupOutbox.DeadLetterQuery;

@ApplicationScoped
@IfBuildProperty(name = "quarkus.datasource.db-kind", stringValue = "postgresql")
@IfBuildProperty(name = "gamelan.workflow.persistence.store", stringValue = "postgres", enableIfMissing = true)
public class PostgresWorkflowRunWakeupOutbox implements WorkflowRunWakeupOutbox {

    private static final int DEFAULT_MAX_PENDING_WAKEUPS = 10_000;
    private static final int DEFAULT_MAX_DELIVERY_ATTEMPTS = 100;
    private static final Duration DEFAULT_LEASE_DURATION = Duration.ofSeconds(30);
    private static final Duration DEFAULT_RETRY_BACKOFF = Duration.ofSeconds(5);

    @Inject
    Pool pgPool;

    @Inject
    ObjectMapper objectMapper;

    @ConfigProperty(name = "gamelan.workflow.wakeup.max-pending", defaultValue = "10000")
    int maxPendingWakeups = DEFAULT_MAX_PENDING_WAKEUPS;

    @ConfigProperty(name = "gamelan.workflow.wakeup.max-delivery-attempts", defaultValue = "100")
    int maxDeliveryAttempts = DEFAULT_MAX_DELIVERY_ATTEMPTS;

    @ConfigProperty(name = "gamelan.workflow.wakeup.lease-duration", defaultValue = "30s")
    Duration leaseDuration = DEFAULT_LEASE_DURATION;

    @ConfigProperty(name = "gamelan.workflow.wakeup.retry-backoff", defaultValue = "5s")
    Duration retryBackoff = DEFAULT_RETRY_BACKOFF;

    @ConfigProperty(name = "gamelan.workflow.wakeup.outbox-owner-id", defaultValue = "")
    String configuredOwnerId = "";

    private final String generatedOwnerId = UUID.randomUUID().toString();

    @Override
    public Uni<WorkflowRunWakeupIntent> enqueue(WorkflowRunUpdateEvent event) {
        Objects.requireNonNull(event, "WorkflowRunUpdateEvent cannot be null");
        String sql = """
                WITH outbox_clock AS (
                    SELECT clock_timestamp() AS now,
                           ($9::bigint * INTERVAL '1 millisecond') AS lease_ttl
                ),
                existing AS (
                    SELECT 1
                    FROM workflow_run_wakeup_outbox
                    WHERE wakeup_key = $1
                ),
                capacity AS (
                    SELECT CASE
                        WHEN $7::int <= 0 THEN true
                        ELSE COUNT(*) < $7::int
                    END AS can_insert
                    FROM workflow_run_wakeup_outbox
                )
                INSERT INTO workflow_run_wakeup_outbox
                    (wakeup_key, intent_id, run_id, tenant_id, reason, event_payload,
                     attempts, created_at, lease_owner, lease_expires_at, updated_at)
                SELECT $1, $2, $3, $4, $5, $6::jsonb, 0,
                       outbox_clock.now, $8, outbox_clock.now + outbox_clock.lease_ttl, outbox_clock.now
                FROM outbox_clock
                WHERE EXISTS (SELECT 1 FROM existing)
                   OR (SELECT can_insert FROM capacity)
                ON CONFLICT (wakeup_key)
                DO UPDATE SET
                    intent_id = EXCLUDED.intent_id,
                    run_id = EXCLUDED.run_id,
                    tenant_id = EXCLUDED.tenant_id,
                    reason = EXCLUDED.reason,
                    event_payload = EXCLUDED.event_payload,
                    lease_owner = EXCLUDED.lease_owner,
                    lease_expires_at = EXCLUDED.lease_expires_at,
                    updated_at = EXCLUDED.updated_at
                RETURNING intent_id, event_payload, attempts, created_at, last_attempt_at, last_error
                """;

        try {
            Duration safeLeaseDuration = positiveDuration(leaseDuration, DEFAULT_LEASE_DURATION);
            return pgPool.preparedQuery(sql)
                    .execute(Tuple.tuple()
                            .addString(coalesceKey(event))
                            .addString(UUID.randomUUID().toString())
                            .addString(event.runId())
                            .addString(event.tenantId())
                            .addString(event.reason())
                            .addString(writeJson(event))
                            .addInteger(maxPendingWakeups)
                            .addString(ownerId())
                            .addLong(Math.max(1L, safeLeaseDuration.toMillis())))
                    .map(rows -> firstIntent(rows, "workflow run wake-up outbox is full"));
        } catch (JsonProcessingException error) {
            return Uni.createFrom().failure(error);
        }
    }

    @Override
    public Uni<List<WorkflowRunWakeupIntent>> pending(int maxItems) {
        String sql = """
                SELECT intent_id, event_payload, attempts, created_at, last_attempt_at, last_error
                FROM workflow_run_wakeup_outbox
                ORDER BY created_at ASC
                LIMIT $1::int
                """;
        int limit = maxItems > 0 ? maxItems : Integer.MAX_VALUE;
        return pgPool.preparedQuery(sql)
                .execute(Tuple.of(limit))
                .map(this::toIntents);
    }

    @Override
    public Uni<List<WorkflowRunWakeupIntent>> claimPending(int maxItems) {
        String sql = """
                WITH claim_clock AS (
                    SELECT clock_timestamp() AS now,
                           ($2::bigint * INTERVAL '1 millisecond') AS lease_ttl,
                           ($4::bigint * INTERVAL '1 millisecond') AS retry_backoff
                ),
                claimable AS (
                    SELECT wakeup_key
                    FROM workflow_run_wakeup_outbox, claim_clock
                    WHERE (
                        lease_owner IS NULL
                        OR lease_expires_at IS NULL
                        OR lease_expires_at <= claim_clock.now
                        OR lease_owner = $3
                    )
                    AND (
                        last_attempt_at IS NULL
                        OR last_attempt_at <= claim_clock.now - claim_clock.retry_backoff
                    )
                    ORDER BY created_at ASC
                    LIMIT $1::int
                    FOR UPDATE SKIP LOCKED
                )
                UPDATE workflow_run_wakeup_outbox wakeup
                SET lease_owner = $3,
                    lease_expires_at = claim_clock.now + claim_clock.lease_ttl,
                    updated_at = claim_clock.now
                FROM claimable, claim_clock
                WHERE wakeup.wakeup_key = claimable.wakeup_key
                RETURNING intent_id, event_payload, attempts, created_at, last_attempt_at, last_error
        """;
        int limit = maxItems > 0 ? maxItems : Integer.MAX_VALUE;
        Duration safeLeaseDuration = positiveDuration(leaseDuration, DEFAULT_LEASE_DURATION);
        Duration safeRetryBackoff = nonNegativeDuration(retryBackoff, DEFAULT_RETRY_BACKOFF);
        return pgPool.preparedQuery(sql)
                .execute(Tuple.tuple()
                        .addInteger(limit)
                        .addLong(Math.max(1L, safeLeaseDuration.toMillis()))
                        .addString(ownerId())
                        .addLong(safeRetryBackoff.toMillis()))
                .map(this::toIntents);
    }

    @Override
    public Uni<Void> markDelivered(String intentId, WorkflowRunUpdateEvent deliveredEvent) {
        String normalizedIntentId = normalizeIntentId(intentId);
        String sql = """
                DELETE FROM workflow_run_wakeup_outbox
                WHERE intent_id = $1
                  AND lease_owner = $2
                  AND ($3::jsonb IS NULL OR event_payload = $3::jsonb)
                """;
        try {
            String deliveredEventJson = deliveredEvent != null ? writeJson(deliveredEvent) : null;
            return pgPool.preparedQuery(sql)
                    .execute(Tuple.tuple()
                            .addString(normalizedIntentId)
                            .addString(ownerId())
                            .addString(deliveredEventJson))
                    .replaceWithVoid();
        } catch (JsonProcessingException error) {
            return Uni.createFrom().failure(error);
        }
    }

    @Override
    public Uni<Void> markFailed(String intentId, Throwable error) {
        Objects.requireNonNull(error, "Wake-up delivery error cannot be null");
        String normalizedIntentId = normalizeIntentId(intentId);
        String sql = """
                WITH failed AS (
                    UPDATE workflow_run_wakeup_outbox
                    SET attempts = attempts + 1,
                        last_attempt_at = NOW(),
                        last_error = $2,
                        lease_owner = NULL,
                        lease_expires_at = NULL,
                        updated_at = NOW()
                    WHERE intent_id = $1
                      AND lease_owner = $3
                    RETURNING wakeup_key, intent_id, run_id, tenant_id, reason, event_payload,
                              attempts, created_at, last_attempt_at, last_error
                ),
                dead_lettered AS (
                    INSERT INTO workflow_run_wakeup_dead_letters
                        (intent_id, wakeup_key, run_id, tenant_id, reason, dead_letter_reason,
                         event_payload, attempts, created_at, last_attempt_at, last_error,
                         dead_lettered_at, updated_at)
                    SELECT intent_id, wakeup_key, run_id, tenant_id, reason, $4,
                           event_payload, attempts, created_at, last_attempt_at, last_error,
                           last_attempt_at, last_attempt_at
                    FROM failed
                    WHERE attempts >= $5::int
                    ON CONFLICT (intent_id)
                    DO UPDATE SET
                        wakeup_key = EXCLUDED.wakeup_key,
                        run_id = EXCLUDED.run_id,
                        tenant_id = EXCLUDED.tenant_id,
                        reason = EXCLUDED.reason,
                        dead_letter_reason = EXCLUDED.dead_letter_reason,
                        event_payload = EXCLUDED.event_payload,
                        attempts = EXCLUDED.attempts,
                        created_at = EXCLUDED.created_at,
                        last_attempt_at = EXCLUDED.last_attempt_at,
                        last_error = EXCLUDED.last_error,
                        dead_lettered_at = EXCLUDED.dead_lettered_at,
                        updated_at = EXCLUDED.updated_at
                    RETURNING wakeup_key
                )
                DELETE FROM workflow_run_wakeup_outbox wakeup
                USING dead_lettered
                WHERE wakeup.wakeup_key = dead_lettered.wakeup_key
                """;
        return pgPool.preparedQuery(sql)
                .execute(Tuple.tuple()
                        .addString(normalizedIntentId)
                        .addString(errorSummary(error))
                        .addString(ownerId())
                        .addString(WorkflowRunWakeupDeadLetterReasons.MAX_DELIVERY_ATTEMPTS_EXCEEDED)
                        .addInteger(effectiveMaxDeliveryAttempts()))
                .replaceWithVoid();
    }

    @Override
    public Uni<List<WorkflowRunWakeupDeadLetter>> deadLetters(int maxItems) {
        return deadLetters(new DeadLetterQuery(maxItems, null, null, null, null));
    }

    @Override
    public Uni<List<WorkflowRunWakeupDeadLetter>> deadLetters(DeadLetterQuery query) {
        QueryStatement statement = deadLetterSelectStatement(query);
        return pgPool.preparedQuery(statement.sql())
                .execute(statement.parameters())
                .map(rows -> {
                    List<WorkflowRunWakeupDeadLetter> deadLetters = new ArrayList<>();
                    for (Row row : rows) {
                        deadLetters.add(toDeadLetter(row));
                    }
                    return deadLetters;
                });
    }

    @Override
    public Uni<Long> deadLetterCount() {
        return deadLetterCount(DeadLetterQuery.all());
    }

    @Override
    public Uni<Long> deadLetterCount(DeadLetterQuery query) {
        QueryStatement statement = deadLetterCountStatement(query);
        return pgPool.preparedQuery(statement.sql())
                .execute(statement.parameters())
                .map(rows -> {
                    var iterator = rows.iterator();
                    return iterator.hasNext() ? iterator.next().getLong(0) : 0L;
                });
    }

    private QueryStatement deadLetterSelectStatement(DeadLetterQuery query) {
        QueryBuilder builder = new QueryBuilder("""
                SELECT intent_id, event_payload, dead_letter_reason, attempts, created_at,
                       last_attempt_at, last_error, dead_lettered_at
                FROM workflow_run_wakeup_dead_letters
                """);
        appendDeadLetterFilters(builder, query);
        builder.sql.append(" ORDER BY dead_lettered_at DESC LIMIT $").append(builder.nextIndex()).append("::int");
        builder.parameters.addInteger(effectiveDeadLetterQuery(query).limit());
        return builder.build();
    }

    private QueryStatement deadLetterCountStatement(DeadLetterQuery query) {
        QueryBuilder builder = new QueryBuilder("""
                SELECT COUNT(*)
                FROM workflow_run_wakeup_dead_letters
                """);
        appendDeadLetterFilters(builder, query);
        return builder.build();
    }

    private void appendDeadLetterFilters(QueryBuilder builder, DeadLetterQuery query) {
        DeadLetterQuery effectiveQuery = effectiveDeadLetterQuery(query);
        List<String> predicates = new ArrayList<>();
        if (effectiveQuery.runId() != null) {
            predicates.add("run_id = $" + builder.nextIndex());
            builder.parameters.addString(effectiveQuery.runId());
        }
        if (effectiveQuery.tenantId() != null) {
            predicates.add("tenant_id = $" + builder.nextIndex());
            builder.parameters.addString(effectiveQuery.tenantId());
        }
        if (effectiveQuery.reason() != null) {
            predicates.add("reason = $" + builder.nextIndex());
            builder.parameters.addString(effectiveQuery.reason());
        }
        if (effectiveQuery.deadLetterReason() != null) {
            predicates.add("dead_letter_reason = $" + builder.nextIndex());
            builder.parameters.addString(effectiveQuery.deadLetterReason());
        }
        if (!predicates.isEmpty()) {
            builder.sql.append(" WHERE ").append(String.join(" AND ", predicates));
        }
    }

    private DeadLetterQuery effectiveDeadLetterQuery(DeadLetterQuery query) {
        return query != null ? query : DeadLetterQuery.all();
    }

    @Override
    public Uni<Optional<WorkflowRunWakeupIntent>> replayDeadLetter(String intentId) {
        String normalizedIntentId = normalizeIntentId(intentId);
        String sql = """
                WITH replay_clock AS (
                    SELECT clock_timestamp() AS now
                ),
                dead_letter AS (
                    SELECT intent_id AS dead_letter_intent_id,
                           wakeup_key, run_id, tenant_id, reason, event_payload
                    FROM workflow_run_wakeup_dead_letters
                    WHERE intent_id = $1
                ),
                capacity AS (
                    SELECT CASE
                        WHEN $3::int <= 0 THEN true
                        ELSE COUNT(*) < $3::int
                    END AS can_insert
                    FROM workflow_run_wakeup_outbox
                ),
                upserted AS (
                    INSERT INTO workflow_run_wakeup_outbox
                        (wakeup_key, intent_id, run_id, tenant_id, reason, event_payload,
                         attempts, created_at, last_attempt_at, last_error,
                         lease_owner, lease_expires_at, updated_at)
                    SELECT dead_letter.wakeup_key, $2, dead_letter.run_id, dead_letter.tenant_id,
                           dead_letter.reason, dead_letter.event_payload, 0, replay_clock.now,
                           NULL, NULL, NULL, NULL, replay_clock.now
                    FROM dead_letter, replay_clock
                    WHERE EXISTS (
                        SELECT 1
                        FROM workflow_run_wakeup_outbox existing
                        WHERE existing.wakeup_key = dead_letter.wakeup_key
                    )
                       OR (SELECT can_insert FROM capacity)
                    ON CONFLICT (wakeup_key)
                    DO UPDATE SET
                        intent_id = EXCLUDED.intent_id,
                        run_id = EXCLUDED.run_id,
                        tenant_id = EXCLUDED.tenant_id,
                        reason = EXCLUDED.reason,
                        event_payload = EXCLUDED.event_payload,
                        attempts = 0,
                        last_attempt_at = NULL,
                        last_error = NULL,
                        lease_owner = NULL,
                        lease_expires_at = NULL,
                        updated_at = EXCLUDED.updated_at
                    RETURNING intent_id, event_payload, attempts, created_at, last_attempt_at, last_error
                ),
                deleted AS (
                    DELETE FROM workflow_run_wakeup_dead_letters
                    WHERE intent_id = $1
                      AND EXISTS (SELECT 1 FROM upserted)
                    RETURNING intent_id
                )
                SELECT intent_id, event_payload, attempts, created_at, last_attempt_at, last_error
                FROM upserted
                """;
        return pgPool.preparedQuery(sql)
                .execute(Tuple.tuple()
                        .addString(normalizedIntentId)
                        .addString(UUID.randomUUID().toString())
                        .addInteger(maxPendingWakeups))
                .map(rows -> firstRow(rows).map(this::toIntent));
    }

    @Override
    public Uni<Boolean> deleteDeadLetter(String intentId) {
        String normalizedIntentId = normalizeIntentId(intentId);
        String sql = """
                DELETE FROM workflow_run_wakeup_dead_letters
                WHERE intent_id = $1
                RETURNING 1
                """;
        return pgPool.preparedQuery(sql)
                .execute(Tuple.of(normalizedIntentId))
                .map(RowSet::iterator)
                .map(iterator -> iterator.hasNext());
    }

    @Override
    public Uni<DeadLetterPurgeResult> purgeDeadLetters(DeadLetterPurgePolicy policy) {
        DeadLetterPurgePolicy effectivePolicy = policy != null ? policy : DeadLetterPurgePolicy.disabled();
        if (!effectivePolicy.hasRetentionCriteria()) {
            return Uni.createFrom().item(DeadLetterPurgeResult.empty(effectivePolicy.dryRun()));
        }
        QueryStatement statement = deadLetterPurgeStatement(effectivePolicy, Instant.now());
        return pgPool.preparedQuery(statement.sql())
                .execute(statement.parameters())
                .map(rows -> {
                    List<String> intentIds = new ArrayList<>();
                    int purged = 0;
                    for (Row row : rows) {
                        intentIds.add(row.getString("intent_id"));
                        if (Boolean.TRUE.equals(row.getBoolean("purged"))) {
                            purged++;
                        }
                    }
                    return new DeadLetterPurgeResult(
                            intentIds.size(),
                            effectivePolicy.dryRun() ? 0 : purged,
                            effectivePolicy.dryRun(),
                            intentIds);
                });
    }

    private QueryStatement deadLetterPurgeStatement(DeadLetterPurgePolicy policy, Instant now) {
        QueryBuilder builder = new QueryBuilder("""
                WITH matched AS (
                    SELECT intent_id, dead_lettered_at
                    FROM workflow_run_wakeup_dead_letters
                """);
        appendDeadLetterFilters(builder, policy.query());
        builder.sql.append("""
                ),
                retention_candidates AS (
                    SELECT intent_id, dead_lettered_at
                    FROM matched
                    ORDER BY dead_lettered_at DESC
                    OFFSET $""").append(builder.nextIndex()).append("::int\n");
        builder.parameters.addInteger(policy.retainLatest() >= 0 ? policy.retainLatest() : 0);
        builder.sql.append("""
                ),
                candidates AS (
                    SELECT intent_id, dead_lettered_at
                    FROM retention_candidates
                """);
        if (policy.olderThan() != null) {
            builder.sql.append("    WHERE dead_lettered_at < $").append(builder.nextIndex()).append("::timestamptz\n");
            builder.parameters.addString(now.minus(policy.olderThan()).toString());
        }
        builder.sql.append("""
                ),
                deleted AS (
                    DELETE FROM workflow_run_wakeup_dead_letters
                    WHERE intent_id IN (SELECT intent_id FROM candidates)
                      AND NOT $""").append(builder.nextIndex()).append("::boolean\n");
        builder.parameters.addBoolean(policy.dryRun());
        builder.sql.append("""
                    RETURNING intent_id
                )
                SELECT candidates.intent_id, deleted.intent_id IS NOT NULL AS purged
                FROM candidates
                LEFT JOIN deleted ON deleted.intent_id = candidates.intent_id
                ORDER BY candidates.dead_lettered_at DESC
                """);
        return builder.build();
    }

    private WorkflowRunWakeupDeadLetter toDeadLetter(Row row) {
        return new WorkflowRunWakeupDeadLetter(
                row.getString("intent_id"),
                readEvent(row.getValue("event_payload")),
                row.getString("dead_letter_reason"),
                row.getInteger("attempts"),
                readInstant(row, "created_at"),
                readOptionalInstant(row, "last_attempt_at"),
                row.getString("last_error"),
                readInstant(row, "dead_lettered_at"));
    }

    private Optional<Row> firstRow(RowSet<Row> rows) {
        var iterator = rows.iterator();
        return iterator.hasNext() ? Optional.of(iterator.next()) : Optional.empty();
    }

    private WorkflowRunWakeupIntent firstIntent(RowSet<Row> rows, String emptyMessage) {
        var iterator = rows.iterator();
        if (!iterator.hasNext()) {
            throw new IllegalStateException(emptyMessage);
        }
        return toIntent(iterator.next());
    }

    private List<WorkflowRunWakeupIntent> toIntents(RowSet<Row> rows) {
        List<WorkflowRunWakeupIntent> intents = new ArrayList<>();
        for (Row row : rows) {
            intents.add(toIntent(row));
        }
        return intents;
    }

    private WorkflowRunWakeupIntent toIntent(Row row) {
        return new WorkflowRunWakeupIntent(
                row.getString("intent_id"),
                readEvent(row.getValue("event_payload")),
                row.getInteger("attempts"),
                readInstant(row, "created_at"),
                readOptionalInstant(row, "last_attempt_at"),
                row.getString("last_error"),
                null);
    }

    private WorkflowRunUpdateEvent readEvent(Object value) {
        try {
            return objectMapper.readValue(jsonText(value), WorkflowRunUpdateEvent.class);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Failed to deserialize workflow run wake-up event payload", error);
        }
    }

    private String jsonText(Object value) throws JsonProcessingException {
        if (value instanceof JsonObject jsonObject) {
            return jsonObject.encode();
        }
        if (value instanceof String text) {
            return text;
        }
        return objectMapper.writeValueAsString(value);
    }

    private String writeJson(Object value) throws JsonProcessingException {
        return objectMapper.writeValueAsString(value);
    }

    private Instant readInstant(Row row, String column) {
        Instant instant = readOptionalInstant(row, column);
        return instant != null ? instant : Instant.now();
    }

    private Instant readOptionalInstant(Row row, String column) {
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
        return offsetDateTime != null ? offsetDateTime.toInstant() : null;
    }

    private static String normalizeIntentId(String intentId) {
        Objects.requireNonNull(intentId, "Wake-up intent id cannot be null");
        if (intentId.isBlank()) {
            throw new IllegalArgumentException("Wake-up intent id cannot be blank");
        }
        return intentId.trim();
    }

    private static String coalesceKey(WorkflowRunUpdateEvent event) {
        return (event.tenantId() != null ? event.tenantId() : "") + ":" + event.runId();
    }

    private String ownerId() {
        return configuredOwnerId != null && !configuredOwnerId.isBlank()
                ? configuredOwnerId.trim()
                : generatedOwnerId;
    }

    private int effectiveMaxDeliveryAttempts() {
        return maxDeliveryAttempts > 0 ? maxDeliveryAttempts : DEFAULT_MAX_DELIVERY_ATTEMPTS;
    }

    private static Duration positiveDuration(Duration value, Duration fallback) {
        return value != null && !value.isZero() && !value.isNegative() ? value : fallback;
    }

    private static Duration nonNegativeDuration(Duration value, Duration fallback) {
        return value != null && !value.isNegative() ? value : fallback;
    }

    private static String errorSummary(Throwable error) {
        String message = error.getMessage();
        return error.getClass().getName() + (message != null && !message.isBlank() ? ": " + message : "");
    }

    private record QueryStatement(String sql, Tuple parameters) {
    }

    private static final class QueryBuilder {
        private final StringBuilder sql;
        private final Tuple parameters = Tuple.tuple();
        private int parameterIndex = 1;

        private QueryBuilder(String sql) {
            this.sql = new StringBuilder(sql);
        }

        private int nextIndex() {
            return parameterIndex++;
        }

        private QueryStatement build() {
            return new QueryStatement(sql.toString(), parameters);
        }
    }
}
