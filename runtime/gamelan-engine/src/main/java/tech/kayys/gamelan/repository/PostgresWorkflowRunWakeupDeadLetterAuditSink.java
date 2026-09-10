package tech.kayys.gamelan.repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.arc.properties.IfBuildProperty;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupDeadLetterAuditEvent;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupDeadLetterAuditSink;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupDeadLetterAuditSink.AuditPurgePolicy;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupDeadLetterAuditSink.AuditPurgeResult;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupDeadLetterAuditSink.AuditQuery;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupDeadLetterAuditSink.AuditSummary;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupDeadLetterAuditSink.AuditSummaryBucket;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupDeadLetterAuditEvent.Operation;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupDeadLetterAuditEvent.Outcome;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupOutbox.DeadLetterQuery;

@ApplicationScoped
@IfBuildProperty(name = "quarkus.datasource.db-kind", stringValue = "postgresql")
@IfBuildProperty(name = "gamelan.workflow.persistence.store", stringValue = "postgres", enableIfMissing = true)
public class PostgresWorkflowRunWakeupDeadLetterAuditSink implements WorkflowRunWakeupDeadLetterAuditSink {

    @Inject
    Pool pgPool;

    @Inject
    ObjectMapper objectMapper;

    @Override
    public Uni<Void> append(WorkflowRunWakeupDeadLetterAuditEvent event) {
        Objects.requireNonNull(event, "Workflow wake-up dead-letter audit event cannot be null");
        String sql = """
                INSERT INTO workflow_run_wakeup_dead_letter_audit
                    (audit_id, operation, outcome, intent_id, query_payload,
                     selected_count, succeeded_count, failed_count, skipped_count,
                     dry_run, intent_ids, error, occurred_at)
                VALUES ($1, $2, $3, $4, $5::jsonb,
                        $6, $7, $8, $9,
                        $10, $11::jsonb, $12, $13::timestamptz)
                """;
        try {
            return pgPool.preparedQuery(sql)
                    .execute(Tuple.tuple()
                            .addString(UUID.randomUUID().toString())
                            .addString(event.operation().name())
                            .addString(event.outcome().name())
                            .addString(event.intentId())
                            .addString(writeJson(event.query()))
                            .addInteger(event.selected())
                            .addInteger(event.succeeded())
                            .addInteger(event.failed())
                            .addInteger(event.skipped())
                            .addBoolean(event.dryRun())
                            .addString(writeJson(event.intentIds()))
                            .addString(event.error())
                            .addString(event.occurredAt().toString()))
                    .replaceWithVoid();
        } catch (JsonProcessingException error) {
            return Uni.createFrom().failure(error);
        }
    }

    @Override
    public Uni<List<WorkflowRunWakeupDeadLetterAuditEvent>> entries(AuditQuery query) {
        QueryStatement statement = auditSelectStatement(query);
        return pgPool.preparedQuery(statement.sql())
                .execute(statement.parameters())
                .map(rows -> {
                    List<WorkflowRunWakeupDeadLetterAuditEvent> events = new ArrayList<>();
                    for (Row row : rows) {
                        events.add(toEvent(row));
                    }
                    return events;
                });
    }

    @Override
    public Uni<Long> count(AuditQuery query) {
        QueryStatement statement = auditCountStatement(query);
        return pgPool.preparedQuery(statement.sql())
                .execute(statement.parameters())
                .map(rows -> {
                    var iterator = rows.iterator();
                    return iterator.hasNext() ? iterator.next().getLong(0) : 0L;
                });
    }

    @Override
    public Uni<AuditSummary> summary(AuditQuery query) {
        AuditQuery effectiveQuery = query != null ? query : AuditQuery.all();
        QueryStatement totalsStatement = auditSummaryTotalsStatement(effectiveQuery);
        QueryStatement bucketsStatement = auditSummaryBucketsStatement(effectiveQuery);
        return pgPool.preparedQuery(totalsStatement.sql())
                .execute(totalsStatement.parameters())
                .flatMap(totalRows -> {
                    AuditSummaryTotals totals = toSummaryTotals(totalRows);
                    return pgPool.preparedQuery(bucketsStatement.sql())
                            .execute(bucketsStatement.parameters())
                            .map(bucketRows -> new AuditSummary(
                                    totals.totalEvents(),
                                    totals.selected(),
                                    totals.succeeded(),
                                    totals.failed(),
                                    totals.skipped(),
                                    toSummaryBuckets(bucketRows)));
                });
    }

    @Override
    public Uni<AuditPurgeResult> purge(AuditPurgePolicy policy) {
        AuditPurgePolicy effectivePolicy = policy != null ? policy : AuditPurgePolicy.disabled();
        if (!effectivePolicy.hasRetentionCriteria()) {
            return Uni.createFrom().item(AuditPurgeResult.empty(effectivePolicy.dryRun()));
        }
        QueryStatement statement = auditPurgeStatement(effectivePolicy, Instant.now());
        return pgPool.preparedQuery(statement.sql())
                .execute(statement.parameters())
                .map(rows -> {
                    List<String> auditIds = new ArrayList<>();
                    int purged = 0;
                    for (Row row : rows) {
                        auditIds.add(row.getString("audit_id"));
                        if (Boolean.TRUE.equals(row.getBoolean("purged"))) {
                            purged++;
                        }
                    }
                    return new AuditPurgeResult(
                            auditIds.size(),
                            effectivePolicy.dryRun() ? 0 : purged,
                            effectivePolicy.dryRun(),
                            auditIds);
                });
    }

    private QueryStatement auditSelectStatement(AuditQuery query) {
        AuditQuery effectiveQuery = query != null ? query : AuditQuery.all();
        QueryBuilder builder = new QueryBuilder("""
                SELECT operation, outcome, intent_id, query_payload,
                       selected_count, succeeded_count, failed_count, skipped_count,
                       dry_run, intent_ids, error, occurred_at
                FROM workflow_run_wakeup_dead_letter_audit
                """);
        appendAuditFilters(builder, effectiveQuery);
        builder.sql.append(" ORDER BY occurred_at DESC LIMIT $").append(builder.nextIndex()).append("::int");
        builder.parameters.addInteger(effectiveQuery.limit());
        return builder.build();
    }

    private QueryStatement auditCountStatement(AuditQuery query) {
        AuditQuery effectiveQuery = query != null ? query : AuditQuery.all();
        QueryBuilder builder = new QueryBuilder("""
                SELECT COUNT(*)
                FROM workflow_run_wakeup_dead_letter_audit
                """);
        appendAuditFilters(builder, effectiveQuery);
        return builder.build();
    }

    private QueryStatement auditSummaryTotalsStatement(AuditQuery query) {
        QueryBuilder builder = new QueryBuilder("""
                SELECT COUNT(*) AS total_events,
                       COALESCE(SUM(selected_count), 0)::bigint AS selected_count,
                       COALESCE(SUM(succeeded_count), 0)::bigint AS succeeded_count,
                       COALESCE(SUM(failed_count), 0)::bigint AS failed_count,
                       COALESCE(SUM(skipped_count), 0)::bigint AS skipped_count
                FROM workflow_run_wakeup_dead_letter_audit
                """);
        appendAuditFilters(builder, query);
        return builder.build();
    }

    private QueryStatement auditSummaryBucketsStatement(AuditQuery query) {
        QueryBuilder builder = new QueryBuilder("""
                SELECT operation, outcome, dry_run, COUNT(*) AS events
                FROM workflow_run_wakeup_dead_letter_audit
                """);
        appendAuditFilters(builder, query);
        builder.sql.append(" GROUP BY operation, outcome, dry_run ORDER BY operation ASC, outcome ASC, dry_run ASC");
        return builder.build();
    }

    private QueryStatement auditPurgeStatement(AuditPurgePolicy policy, Instant now) {
        QueryBuilder builder = new QueryBuilder("""
                WITH matched AS (
                    SELECT audit_id, occurred_at
                    FROM workflow_run_wakeup_dead_letter_audit
                """);
        appendAuditFilters(builder, policy.query());
        builder.sql.append("""
                ),
                retention_candidates AS (
                    SELECT audit_id, occurred_at
                    FROM matched
                    ORDER BY occurred_at DESC
                    OFFSET $""").append(builder.nextIndex()).append("::int\n");
        builder.parameters.addInteger(policy.retainLatest() >= 0 ? policy.retainLatest() : 0);
        builder.sql.append("""
                ),
                candidates AS (
                    SELECT audit_id, occurred_at
                    FROM retention_candidates
                """);
        if (policy.olderThan() != null) {
            builder.sql.append("    WHERE occurred_at < $").append(builder.nextIndex()).append("::timestamptz\n");
            builder.parameters.addString(now.minus(policy.olderThan()).toString());
        }
        builder.sql.append("""
                ),
                deleted AS (
                    DELETE FROM workflow_run_wakeup_dead_letter_audit
                    WHERE audit_id IN (SELECT audit_id FROM candidates)
                      AND NOT $""").append(builder.nextIndex()).append("::boolean\n");
        builder.parameters.addBoolean(policy.dryRun());
        builder.sql.append("""
                    RETURNING audit_id
                )
                SELECT candidates.audit_id, deleted.audit_id IS NOT NULL AS purged
                FROM candidates
                LEFT JOIN deleted ON deleted.audit_id = candidates.audit_id
                ORDER BY candidates.occurred_at DESC
                """);
        return builder.build();
    }

    private void appendAuditFilters(QueryBuilder builder, AuditQuery query) {
        List<String> predicates = new ArrayList<>();
        if (query.operation() != null) {
            predicates.add("operation = $" + builder.nextIndex());
            builder.parameters.addString(query.operation().name());
        }
        if (query.outcome() != null) {
            predicates.add("outcome = $" + builder.nextIndex());
            builder.parameters.addString(query.outcome().name());
        }
        if (query.intentId() != null) {
            int index = builder.nextIndex();
            predicates.add("(intent_id = $" + index + " OR intent_ids ? $" + index + ")");
            builder.parameters.addString(query.intentId());
        }
        if (query.runId() != null) {
            predicates.add("query_payload ->> 'runId' = $" + builder.nextIndex());
            builder.parameters.addString(query.runId());
        }
        if (query.tenantId() != null) {
            predicates.add("query_payload ->> 'tenantId' = $" + builder.nextIndex());
            builder.parameters.addString(query.tenantId());
        }
        if (query.dryRun() != null) {
            predicates.add("dry_run = $" + builder.nextIndex());
            builder.parameters.addBoolean(query.dryRun());
        }
        if (query.occurredFrom() != null) {
            predicates.add("occurred_at >= $" + builder.nextIndex() + "::timestamptz");
            builder.parameters.addString(query.occurredFrom().toString());
        }
        if (query.occurredTo() != null) {
            predicates.add("occurred_at <= $" + builder.nextIndex() + "::timestamptz");
            builder.parameters.addString(query.occurredTo().toString());
        }
        if (!predicates.isEmpty()) {
            builder.sql.append(" WHERE ").append(String.join(" AND ", predicates));
        }
    }

    private AuditSummaryTotals toSummaryTotals(Iterable<Row> rows) {
        var iterator = rows.iterator();
        if (!iterator.hasNext()) {
            return new AuditSummaryTotals(0, 0, 0, 0, 0);
        }
        Row row = iterator.next();
        return new AuditSummaryTotals(
                longValue(row, "total_events"),
                longValue(row, "selected_count"),
                longValue(row, "succeeded_count"),
                longValue(row, "failed_count"),
                longValue(row, "skipped_count"));
    }

    private List<AuditSummaryBucket> toSummaryBuckets(Iterable<Row> rows) {
        List<AuditSummaryBucket> buckets = new ArrayList<>();
        for (Row row : rows) {
            buckets.add(new AuditSummaryBucket(
                    Operation.valueOf(row.getString("operation")),
                    Outcome.valueOf(row.getString("outcome")),
                    Boolean.TRUE.equals(row.getBoolean("dry_run")),
                    longValue(row, "events")));
        }
        return buckets;
    }

    private WorkflowRunWakeupDeadLetterAuditEvent toEvent(Row row) {
        return new WorkflowRunWakeupDeadLetterAuditEvent(
                Operation.valueOf(row.getString("operation")),
                Outcome.valueOf(row.getString("outcome")),
                row.getString("intent_id"),
                readQuery(row.getValue("query_payload")),
                row.getInteger("selected_count"),
                row.getInteger("succeeded_count"),
                row.getInteger("failed_count"),
                row.getInteger("skipped_count"),
                Boolean.TRUE.equals(row.getBoolean("dry_run")),
                readIntentIds(row.getValue("intent_ids")),
                row.getString("error"),
                readInstant(row, "occurred_at"));
    }

    private long longValue(Row row, String column) {
        Long value = row.getLong(column);
        return value != null ? value : 0L;
    }

    private DeadLetterQuery readQuery(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.readValue(jsonText(value), DeadLetterQuery.class);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Failed to deserialize workflow wake-up dead-letter audit query", error);
        }
    }

    private List<String> readIntentIds(Object value) {
        if (value == null) {
            return List.of();
        }
        try {
            return objectMapper.readValue(jsonText(value), new TypeReference<List<String>>() {
            });
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Failed to deserialize workflow wake-up dead-letter audit intent ids", error);
        }
    }

    private String jsonText(Object value) throws JsonProcessingException {
        if (value instanceof JsonObject jsonObject) {
            return jsonObject.encode();
        }
        if (value instanceof JsonArray jsonArray) {
            return jsonArray.encode();
        }
        if (value instanceof String text) {
            return text;
        }
        return objectMapper.writeValueAsString(value);
    }

    private String writeJson(Object value) throws JsonProcessingException {
        return value != null ? objectMapper.writeValueAsString(value) : null;
    }

    private Instant readInstant(Row row, String column) {
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

    private record QueryStatement(String sql, Tuple parameters) {
    }

    private record AuditSummaryTotals(
            long totalEvents,
            long selected,
            long succeeded,
            long failed,
            long skipped) {
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
