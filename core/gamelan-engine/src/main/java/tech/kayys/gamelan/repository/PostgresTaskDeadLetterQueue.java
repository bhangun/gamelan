package tech.kayys.gamelan.repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
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
import tech.kayys.gamelan.engine.node.NodeExecutionTask;
import tech.kayys.gamelan.scheduler.TaskDeadLetterQueue;

@ApplicationScoped
@IfBuildProperty(name = "quarkus.datasource.db-kind", stringValue = "postgresql")
@IfBuildProperty(name = "gamelan.workflow.persistence.store", stringValue = "postgres", enableIfMissing = true)
public class PostgresTaskDeadLetterQueue implements TaskDeadLetterQueue {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    @Inject
    Pool pgPool;

    @Inject
    ObjectMapper objectMapper;

    @Override
    public Uni<Void> publish(DeadLetterTask task) {
        String sql = """
                INSERT INTO task_dead_letters
                    (message_id, run_id, tenant_id, node_id, reason, delivery_attempt, defer_count,
                     first_seen_at, dead_lettered_at, task_payload, diagnostics, updated_at)
                VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10::jsonb, $11::jsonb, NOW())
                ON CONFLICT (message_id)
                DO UPDATE SET
                    run_id = EXCLUDED.run_id,
                    tenant_id = EXCLUDED.tenant_id,
                    node_id = EXCLUDED.node_id,
                    reason = EXCLUDED.reason,
                    delivery_attempt = EXCLUDED.delivery_attempt,
                    defer_count = EXCLUDED.defer_count,
                    first_seen_at = EXCLUDED.first_seen_at,
                    dead_lettered_at = EXCLUDED.dead_lettered_at,
                    task_payload = EXCLUDED.task_payload,
                    diagnostics = EXCLUDED.diagnostics,
                    updated_at = NOW()
                """;

        try {
            return pgPool.preparedQuery(sql)
                    .execute(Tuple.tuple()
                            .addString(task.messageId())
                            .addString(task.task().runId().value())
                            .addString(tenantId(task.task()))
                            .addString(task.task().nodeId().value())
                            .addString(task.reason())
                            .addInteger(task.deliveryAttempt())
                            .addInteger(task.deferCount())
                            .addValue(task.firstSeenAt())
                            .addValue(task.deadLetteredAt())
                            .addString(writeJson(task.task()))
                            .addString(writeJson(task.diagnostics())))
                    .replaceWithVoid();
        } catch (JsonProcessingException error) {
            return Uni.createFrom().failure(error);
        }
    }

    @Override
    public Uni<List<DeadLetterTask>> list(int limit) {
        return list(new DeadLetterQuery(limit, null, null, null, null));
    }

    @Override
    public Uni<List<DeadLetterTask>> list(DeadLetterQuery query) {
        QueryStatement statement = selectStatement(query);
        return pgPool.preparedQuery(statement.sql())
                .execute(statement.parameters())
                .map(rows -> {
                    List<DeadLetterTask> entries = new ArrayList<>();
                    for (Row row : rows) {
                        entries.add(toDeadLetter(row));
                    }
                    return entries;
                });
    }

    @Override
    public Uni<Long> count() {
        return count(DeadLetterQuery.all());
    }

    @Override
    public Uni<Long> count(DeadLetterQuery query) {
        QueryStatement statement = countStatement(query);
        return pgPool.preparedQuery(statement.sql())
                .execute(statement.parameters())
                .map(rows -> {
                    var iterator = rows.iterator();
                    return iterator.hasNext() ? iterator.next().getLong(0) : 0L;
                });
    }

    @Override
    public Uni<Optional<DeadLetterTask>> get(String messageId) {
        String normalizedMessageId = normalizeMessageId(messageId);
        if (normalizedMessageId == null) {
            return Uni.createFrom().item(Optional.empty());
        }

        String sql = """
                SELECT message_id, task_payload, reason, delivery_attempt, defer_count,
                       first_seen_at, dead_lettered_at, diagnostics
                FROM task_dead_letters
                WHERE message_id = $1
                """;

        return pgPool.preparedQuery(sql)
                .execute(Tuple.of(normalizedMessageId))
                .map(rows -> firstRow(rows).map(this::toDeadLetter));
    }

    @Override
    public Uni<Boolean> delete(String messageId) {
        String normalizedMessageId = normalizeMessageId(messageId);
        if (normalizedMessageId == null) {
            return Uni.createFrom().item(false);
        }

        String sql = """
                DELETE FROM task_dead_letters
                WHERE message_id = $1
                RETURNING 1
                """;

        return pgPool.preparedQuery(sql)
                .execute(Tuple.of(normalizedMessageId))
                .map(RowSet::iterator)
                .map(iterator -> iterator.hasNext());
    }

    @Override
    public Uni<Long> clear(DeadLetterQuery query) {
        QueryStatement statement = deleteStatement(query);
        return pgPool.preparedQuery(statement.sql())
                .execute(statement.parameters())
                .map(this::countRows);
    }

    @Override
    public Uni<Void> clear() {
        return pgPool.preparedQuery("DELETE FROM task_dead_letters")
                .execute(Tuple.tuple())
                .replaceWithVoid();
    }

    private QueryStatement selectStatement(DeadLetterQuery query) {
        QueryBuilder builder = new QueryBuilder("""
                SELECT message_id, task_payload, reason, delivery_attempt, defer_count,
                       first_seen_at, dead_lettered_at, diagnostics
                FROM task_dead_letters
                """);
        appendFilters(builder, query);
        builder.sql.append(" ORDER BY dead_lettered_at DESC LIMIT $").append(builder.nextIndex()).append("::int");
        builder.parameters.addInteger(effectiveQuery(query).limit());
        return builder.build();
    }

    private QueryStatement countStatement(DeadLetterQuery query) {
        QueryBuilder builder = new QueryBuilder("""
                SELECT COUNT(*)
                FROM task_dead_letters
                """);
        appendFilters(builder, query);
        return builder.build();
    }

    private QueryStatement deleteStatement(DeadLetterQuery query) {
        QueryBuilder builder = new QueryBuilder("""
                DELETE FROM task_dead_letters
                """);
        appendFilters(builder, query);
        builder.sql.append(" RETURNING 1");
        return builder.build();
    }

    private void appendFilters(QueryBuilder builder, DeadLetterQuery query) {
        DeadLetterQuery effectiveQuery = effectiveQuery(query);
        List<String> predicates = new ArrayList<>();
        if (effectiveQuery.runId() != null) {
            predicates.add("run_id = $" + builder.nextIndex());
            builder.parameters.addString(effectiveQuery.runId());
        }
        if (effectiveQuery.nodeId() != null) {
            predicates.add("node_id = $" + builder.nextIndex());
            builder.parameters.addString(effectiveQuery.nodeId());
        }
        if (effectiveQuery.tenantId() != null) {
            predicates.add("tenant_id = $" + builder.nextIndex());
            builder.parameters.addString(effectiveQuery.tenantId());
        }
        if (effectiveQuery.reason() != null) {
            predicates.add("reason = $" + builder.nextIndex());
            builder.parameters.addString(effectiveQuery.reason());
        }
        if (!predicates.isEmpty()) {
            builder.sql.append(" WHERE ").append(String.join(" AND ", predicates));
        }
    }

    private DeadLetterQuery effectiveQuery(DeadLetterQuery query) {
        return query != null ? query : DeadLetterQuery.all();
    }

    private Optional<Row> firstRow(RowSet<Row> rows) {
        var iterator = rows.iterator();
        return iterator.hasNext() ? Optional.of(iterator.next()) : Optional.empty();
    }

    private long countRows(RowSet<Row> rows) {
        long count = 0;
        for (Row ignored : rows) {
            count++;
        }
        return count;
    }

    private DeadLetterTask toDeadLetter(Row row) {
        return new DeadLetterTask(
                row.getString("message_id"),
                readTask(row.getValue("task_payload")),
                row.getString("reason"),
                row.getInteger("delivery_attempt"),
                row.getInteger("defer_count"),
                readInstant(row, "first_seen_at"),
                readInstant(row, "dead_lettered_at"),
                readJsonMap(row.getValue("diagnostics")));
    }

    private NodeExecutionTask readTask(Object value) {
        try {
            return objectMapper.readValue(jsonText(value), NodeExecutionTask.class);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Failed to deserialize task dead-letter payload", error);
        }
    }

    private Map<String, Object> readJsonMap(Object value) {
        if (value == null) {
            return Map.of();
        }
        if (value instanceof JsonObject jsonObject) {
            return copyMap(jsonObject.getMap());
        }
        if (value instanceof Map<?, ?> map) {
            return copyMap(map);
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return objectMapper.readValue(text, MAP_TYPE);
            } catch (JsonProcessingException error) {
                throw new IllegalStateException("Failed to deserialize task dead-letter diagnostics", error);
            }
        }
        return Map.of();
    }

    private Map<String, Object> copyMap(Map<?, ?> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (entry.getKey() != null) {
                copy.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return copy;
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

    private static String tenantId(NodeExecutionTask task) {
        Object value = task.context().get(NodeExecutionTask.TENANT_ID_KEY);
        return value instanceof String text && !text.isBlank() ? text.trim() : null;
    }

    private static String normalizeMessageId(String messageId) {
        return messageId != null && !messageId.isBlank() ? messageId.trim() : null;
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
