package tech.kayys.gamelan.repository;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.arc.properties.IfBuildProperty;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.RowSet;
import io.vertx.mutiny.sqlclient.SqlClient;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import tech.kayys.gamelan.engine.ExecutionEventTypes;
import tech.kayys.gamelan.engine.event.ExecutionEvent;
import tech.kayys.gamelan.engine.event.GenericExecutionEvent;
import tech.kayys.gamelan.engine.execution.ExecutionHistoryAppendConflictException;
import tech.kayys.gamelan.engine.execution.ExecutionEventEnvelopes;
import tech.kayys.gamelan.engine.execution.ExecutionHistory;
import tech.kayys.gamelan.engine.execution.ExecutionHistoryRepository;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;

@ApplicationScoped
@jakarta.enterprise.inject.Alternative
@IfBuildProperty(name = "quarkus.datasource.db-kind", stringValue = "postgresql")
@IfBuildProperty(name = "gamelan.workflow.persistence.store", stringValue = "postgres", enableIfMissing = true)
public class PostgresExecutionHistoryRepository implements ExecutionHistoryRepository {

    private static final Logger LOG = LoggerFactory.getLogger(PostgresExecutionHistoryRepository.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    @Inject
    Pool pgPool;

    @Inject
    ObjectMapper objectMapper;

    @Override
    public Uni<Void> append(WorkflowRunId runId, String type, String message, Map<String, Object> metadata) {
        return append(runId, null, type, message, metadata);
    }

    @Override
    public Uni<Void> append(
            WorkflowRunId runId,
            TenantId tenantId,
            String type,
            String message,
            Map<String, Object> metadata) {
        Map<String, Object> eventData = new LinkedHashMap<>();
        eventData.put("message", message);
        eventData.put("metadata", metadata != null ? metadata : Map.of());
        eventData.put("source", "execution-history");

        return appendEventRow(
                runId,
                tenantId,
                UUID.randomUUID().toString(),
                type,
                eventData,
                metadata != null ? metadata : Map.of(),
                Instant.now());
    }

    @Override
    public Uni<Void> appendEvents(WorkflowRunId runId, List<ExecutionEvent> events) {
        return appendEvents(runId, null, events);
    }

    @Override
    public Uni<Void> appendEvents(WorkflowRunId runId, TenantId tenantId, List<ExecutionEvent> events) {
        List<ExecutionEvent> safeEvents;
        List<DomainEventAppendRecord> eventBatch;
        try {
            safeEvents = ExecutionEventEnvelopes.validateForRun(runId, tenantId, events);
            eventBatch = prepareDomainEvents(safeEvents);
        } catch (IllegalArgumentException error) {
            return Uni.createFrom().failure(error);
        }
        if (eventBatch.isEmpty()) {
            return Uni.createFrom().voidItem();
        }

        return pgPool.withTransaction(connection -> appendDomainEventBatch(connection, runId, tenantId, eventBatch));
    }

    private Uni<Void> appendDomainEventBatch(
            SqlClient client,
            WorkflowRunId runId,
            TenantId tenantId,
            List<DomainEventAppendRecord> events) {
        Uni<Void> chain = Uni.createFrom().voidItem();
        for (DomainEventAppendRecord event : events) {
            chain = chain.chain(() -> appendEventRow(
                    client,
                    runId,
                    tenantId,
                    event.eventId(),
                    event.eventType(),
                    event.eventData(),
                    event.metadata(),
                    event.occurredAt()));
        }
        return chain;
    }

    @Override
    public Uni<ExecutionHistory> load(WorkflowRunId runId) {
        String sql = """
                SELECT event_id, event_type, event_data, occurred_at, metadata
                FROM workflow_events
                WHERE run_id = $1
                AND tenant_id = 'system'
                ORDER BY sequence_number ASC
                """;

        return loadEvents(sql, Tuple.of(runId.value()), runId);
    }

    @Override
    public Uni<ExecutionHistory> load(WorkflowRunId runId, TenantId tenantId) {
        if (tenantId == null) {
            return load(runId);
        }
        String sql = """
                WITH tenant_stream AS (
                    SELECT EXISTS(
                        SELECT 1
                        FROM workflow_events
                        WHERE run_id = $1
                        AND tenant_id = $2
                    ) AS has_tenant_events
                )
                SELECT event_id, tenant_id, event_type, event_data, occurred_at, metadata
                FROM workflow_events, tenant_stream
                WHERE run_id = $1
                AND (
                    tenant_id = $2
                    OR (tenant_id = 'system' AND NOT tenant_stream.has_tenant_events)
                )
                ORDER BY sequence_number ASC
                """;

        return loadEvents(sql, Tuple.of(runId.value(), tenantValue(tenantId)), runId);
    }

    @Override
    public Uni<Boolean> isNodeResultProcessed(WorkflowRunId runId, NodeId nodeId, int attempt) {
        String sql = """
                SELECT EXISTS(
                    SELECT 1
                    FROM workflow_processed_node_results
                    WHERE run_id = $1
                    AND tenant_id IS NULL
                    AND node_id = $2
                    AND attempt = $3
                )
                """;

        return exists(sql, Tuple.of(runId.value(), nodeId.value(), attempt));
    }

    @Override
    public Uni<Boolean> isNodeResultProcessed(
            WorkflowRunId runId,
            TenantId tenantId,
            NodeId nodeId,
            int attempt) {
        if (tenantId == null) {
            return isNodeResultProcessed(runId, nodeId, attempt);
        }

        String sql = """
                SELECT EXISTS(
                    SELECT 1
                    FROM workflow_processed_node_results
                    WHERE run_id = $1
                    AND (tenant_id IS NULL OR tenant_id = $2)
                    AND node_id = $3
                    AND attempt = $4
                )
                """;

        return exists(sql, Tuple.of(runId.value(), tenantId.value(), nodeId.value(), attempt));
    }

    @Override
    public Uni<Boolean> markNodeResultProcessed(WorkflowRunId runId, NodeId nodeId, int attempt) {
        String sql = """
                INSERT INTO workflow_processed_node_results
                (run_id, tenant_id, node_id, attempt, processed_at)
                VALUES ($1, NULL, $2, $3, $4)
                ON CONFLICT DO NOTHING
                RETURNING 1
                """;

        return inserted(sql, Tuple.of(runId.value(), nodeId.value(), attempt, Instant.now()));
    }

    @Override
    public Uni<Boolean> markNodeResultProcessed(
            WorkflowRunId runId,
            TenantId tenantId,
            NodeId nodeId,
            int attempt) {
        if (tenantId == null) {
            return markNodeResultProcessed(runId, nodeId, attempt);
        }

        String sql = """
                INSERT INTO workflow_processed_node_results
                (run_id, tenant_id, node_id, attempt, processed_at)
                SELECT $1, $2, $3, $4, $5
                WHERE NOT EXISTS (
                    SELECT 1
                    FROM workflow_processed_node_results
                    WHERE run_id = $1
                    AND tenant_id IS NULL
                    AND node_id = $3
                    AND attempt = $4
                )
                ON CONFLICT DO NOTHING
                RETURNING 1
                """;

        return inserted(sql, Tuple.of(runId.value(), tenantId.value(), nodeId.value(), attempt, Instant.now()));
    }

    @Override
    public Uni<Boolean> isExternalSignalProcessed(WorkflowRunId runId, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Uni.createFrom().item(false);
        }

        String sql = """
                SELECT EXISTS(
                    SELECT 1
                    FROM workflow_processed_external_signals
                    WHERE run_id = $1
                    AND tenant_id IS NULL
                    AND idempotency_key = $2
                )
                """;

        return exists(sql, Tuple.of(runId.value(), idempotencyKey));
    }

    @Override
    public Uni<Boolean> isExternalSignalProcessed(
            WorkflowRunId runId,
            TenantId tenantId,
            String idempotencyKey) {
        if (tenantId == null) {
            return isExternalSignalProcessed(runId, idempotencyKey);
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Uni.createFrom().item(false);
        }

        String sql = """
                SELECT EXISTS(
                    SELECT 1
                    FROM workflow_processed_external_signals
                    WHERE run_id = $1
                    AND (tenant_id IS NULL OR tenant_id = $2)
                    AND idempotency_key = $3
                )
                """;

        return exists(sql, Tuple.of(runId.value(), tenantId.value(), idempotencyKey));
    }

    @Override
    public Uni<Boolean> markExternalSignalProcessed(WorkflowRunId runId, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Uni.createFrom().item(false);
        }

        String sql = """
                INSERT INTO workflow_processed_external_signals
                (run_id, tenant_id, idempotency_key, processed_at)
                VALUES ($1, NULL, $2, $3)
                ON CONFLICT DO NOTHING
                RETURNING 1
                """;

        return inserted(sql, Tuple.of(runId.value(), idempotencyKey, Instant.now()));
    }

    @Override
    public Uni<Boolean> markExternalSignalProcessed(
            WorkflowRunId runId,
            TenantId tenantId,
            String idempotencyKey) {
        if (tenantId == null) {
            return markExternalSignalProcessed(runId, idempotencyKey);
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Uni.createFrom().item(false);
        }

        String sql = """
                INSERT INTO workflow_processed_external_signals
                (run_id, tenant_id, idempotency_key, processed_at)
                SELECT $1, $2, $3, $4
                WHERE NOT EXISTS (
                    SELECT 1
                    FROM workflow_processed_external_signals
                    WHERE run_id = $1
                    AND tenant_id IS NULL
                    AND idempotency_key = $3
                )
                ON CONFLICT DO NOTHING
                RETURNING 1
                """;

        return inserted(sql, Tuple.of(runId.value(), tenantId.value(), idempotencyKey, Instant.now()));
    }

    @Override
    public Uni<Boolean> appendSignalReceivedAudit(
            WorkflowRunId runId,
            TenantId tenantId,
            String idempotencyKey,
            String signalName,
            Map<String, Object> metadata) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Uni.createFrom().item(false);
        }

        Map<String, Object> safeMetadata = metadata != null ? metadata : Map.of();
        Map<String, Object> eventData = new LinkedHashMap<>();
        eventData.put("message", signalName);
        eventData.put("metadata", safeMetadata);
        eventData.put("source", "execution-history");

        String eventId = signalAuditEventId("signal-received", runId, tenantId, idempotencyKey);
        return insertEventRow(
                pgPool,
                runId,
                tenantId,
                eventId,
                ExecutionEventTypes.SIGNAL_RECEIVED,
                eventData,
                safeMetadata,
                Instant.now())
                .invoke(inserted -> {
                    if (!inserted) {
                        LOG.debug(
                                "Signal audit already exists for run: {} tenant: {} idempotencyKey: {}",
                                runId.value(),
                                tenantValue(tenantId),
                                idempotencyKey);
                    }
                })
                .onFailure()
                .invoke(throwable -> logAppendFailure(runId, tenantId, throwable));
    }

    @Override
    public Uni<Boolean> appendSignalIgnoredAudit(
            WorkflowRunId runId,
            TenantId tenantId,
            String idempotencyKey,
            String reason,
            Map<String, Object> metadata) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Uni.createFrom().item(false);
        }

        Map<String, Object> safeMetadata = metadata != null ? metadata : Map.of();
        Map<String, Object> eventData = new LinkedHashMap<>();
        eventData.put("message", reason);
        eventData.put("metadata", safeMetadata);
        eventData.put("source", "execution-history");

        String eventId = signalAuditEventId("signal-ignored", runId, tenantId, idempotencyKey);
        return insertEventRow(
                pgPool,
                runId,
                tenantId,
                eventId,
                ExecutionEventTypes.SIGNAL_IGNORED,
                eventData,
                safeMetadata,
                Instant.now())
                .invoke(inserted -> {
                    if (!inserted) {
                        LOG.debug(
                                "Ignored signal audit already exists for run: {} tenant: {} idempotencyKey: {}",
                                runId.value(),
                                tenantValue(tenantId),
                                idempotencyKey);
                    }
                })
                .onFailure()
                .invoke(throwable -> logAppendFailure(runId, tenantId, throwable));
    }

    @Override
    public Uni<Boolean> isCompensationNodeProcessed(WorkflowRunId runId, NodeId nodeId) {
        String sql = """
                SELECT EXISTS(
                    SELECT 1
                    FROM workflow_processed_compensation_nodes
                    WHERE run_id = $1
                    AND tenant_id IS NULL
                    AND node_id = $2
                )
                """;

        return exists(sql, Tuple.of(runId.value(), nodeId.value()));
    }

    @Override
    public Uni<Boolean> isCompensationNodeProcessed(
            WorkflowRunId runId,
            TenantId tenantId,
            NodeId nodeId) {
        if (tenantId == null) {
            return isCompensationNodeProcessed(runId, nodeId);
        }

        String sql = """
                SELECT EXISTS(
                    SELECT 1
                    FROM workflow_processed_compensation_nodes
                    WHERE run_id = $1
                    AND (tenant_id IS NULL OR tenant_id = $2)
                    AND node_id = $3
                )
                """;

        return exists(sql, Tuple.of(runId.value(), tenantId.value(), nodeId.value()));
    }

    @Override
    public Uni<Boolean> markCompensationNodeProcessed(WorkflowRunId runId, NodeId nodeId) {
        String sql = """
                INSERT INTO workflow_processed_compensation_nodes
                (run_id, tenant_id, node_id, processed_at)
                VALUES ($1, NULL, $2, $3)
                ON CONFLICT DO NOTHING
                RETURNING 1
                """;

        return inserted(sql, Tuple.of(runId.value(), nodeId.value(), Instant.now()));
    }

    @Override
    public Uni<Boolean> markCompensationNodeProcessed(
            WorkflowRunId runId,
            TenantId tenantId,
            NodeId nodeId) {
        if (tenantId == null) {
            return markCompensationNodeProcessed(runId, nodeId);
        }

        String sql = """
                INSERT INTO workflow_processed_compensation_nodes
                (run_id, tenant_id, node_id, processed_at)
                SELECT $1, $2, $3, $4
                WHERE NOT EXISTS (
                    SELECT 1
                    FROM workflow_processed_compensation_nodes
                    WHERE run_id = $1
                    AND tenant_id IS NULL
                    AND node_id = $3
                )
                ON CONFLICT DO NOTHING
                RETURNING 1
                """;

        return inserted(sql, Tuple.of(runId.value(), tenantId.value(), nodeId.value(), Instant.now()));
    }

    private List<DomainEventAppendRecord> prepareDomainEvents(List<ExecutionEvent> events) {
        List<DomainEventAppendRecord> prepared = new ArrayList<>(events.size());
        for (ExecutionEvent event : events) {
            Map<String, Object> eventData = objectMapper.convertValue(event, MAP_TYPE);
            String eventType = ExecutionEventEnvelopes.safeEventType(event);
            Map<String, Object> metadata = Map.of(
                    "source", "domain-event",
                    "domainEventType", eventType);

            prepared.add(new DomainEventAppendRecord(
                    event.eventId() != null ? event.eventId() : UUID.randomUUID().toString(),
                    eventType,
                    eventData,
                    metadata,
                    event.occurredAt() != null ? event.occurredAt() : Instant.now()));
        }
        return prepared;
    }

    private Uni<Void> appendEventRow(
            WorkflowRunId runId,
            TenantId tenantId,
            String eventId,
            String eventType,
            Map<String, Object> eventData,
            Map<String, Object> metadata,
            Instant occurredAt) {
        return appendEventRow(pgPool, runId, tenantId, eventId, eventType, eventData, metadata, occurredAt);
    }

    private Uni<Void> appendEventRow(
            SqlClient client,
            WorkflowRunId runId,
            TenantId tenantId,
            String eventId,
            String eventType,
            Map<String, Object> eventData,
            Map<String, Object> metadata,
            Instant occurredAt) {
        return insertEventRow(client, runId, tenantId, eventId, eventType, eventData, metadata, occurredAt)
                .chain(inserted -> inserted
                        ? Uni.createFrom().voidItem()
                        : Uni.createFrom().failure(new ExecutionHistoryAppendConflictException(
                                eventId,
                                runId,
                                tenantId)))
                .onFailure()
                .invoke(throwable -> logAppendFailure(runId, tenantId, throwable));
    }

    private Uni<Boolean> insertEventRow(
            SqlClient client,
            WorkflowRunId runId,
            TenantId tenantId,
            String eventId,
            String eventType,
            Map<String, Object> eventData,
            Map<String, Object> metadata,
            Instant occurredAt) {
        String sql = """
                WITH run_lock AS (
                    SELECT pg_advisory_xact_lock(hashtext($3 || ':' || $2)::bigint) AS locked
                ),
                next_sequence AS (
                    SELECT COALESCE(MAX(workflow_events.sequence_number), 0) + 1 AS sequence_number
                    FROM run_lock
                    LEFT JOIN workflow_events ON workflow_events.run_id = $2
                    AND workflow_events.tenant_id = $3
                )
                INSERT INTO workflow_events
                (event_id, run_id, tenant_id, event_type, sequence_number, event_data, occurred_at, metadata)
                SELECT $1, $2, $3, $4, next_sequence.sequence_number, $5::jsonb, $6, $7::jsonb
                FROM next_sequence
                ON CONFLICT (event_id) DO NOTHING
                RETURNING 1
                """;

        try {
            return client.preparedQuery(sql)
                    .execute(Tuple.tuple()
                            .addValue(eventId)
                            .addValue(runId.value())
                            .addValue(tenantValue(tenantId))
                            .addValue(eventType)
                            .addValue(writeJson(eventData))
                            .addValue(occurredAt)
                            .addValue(writeJson(metadata != null ? metadata : Map.of())))
                    .map(rows -> rows.rowCount() == 1 || rows.iterator().hasNext());
        } catch (JsonProcessingException error) {
            return Uni.createFrom().failure(error);
        }
    }

    private String signalAuditEventId(
            String auditType,
            WorkflowRunId runId,
            TenantId tenantId,
            String idempotencyKey) {
        String canonical = auditType
                + "|"
                + tenantValue(tenantId)
                + "|"
                + runId.value()
                + "|"
                + idempotencyKey;
        return auditType + "-" + UUID.nameUUIDFromBytes(canonical.getBytes(StandardCharsets.UTF_8));
    }

    private void logAppendFailure(WorkflowRunId runId, TenantId tenantId, Throwable throwable) {
        if (throwable instanceof ExecutionHistoryAppendConflictException conflict) {
            LOG.debug(
                    "Execution history event append conflict for event: {} run: {} tenant: {}",
                    conflict.eventId(),
                    runId.value(),
                    tenantValue(tenantId));
            return;
        }
        LOG.error("Failed to append execution history event for run: {}", runId.value(), throwable);
    }

    private record DomainEventAppendRecord(
            String eventId,
            String eventType,
            Map<String, Object> eventData,
            Map<String, Object> metadata,
            Instant occurredAt) {
    }

    private Uni<ExecutionHistory> loadEvents(String sql, Tuple parameters, WorkflowRunId runId) {
        return pgPool.preparedQuery(sql)
                .execute(parameters)
                .map(rows -> {
                    List<ExecutionEvent> events = new ArrayList<>();
                    for (Row row : rows) {
                        events.add(toGenericEvent(runId, row));
                    }
                    return ExecutionHistory.fromEvents(runId, events);
                });
    }

    private GenericExecutionEvent toGenericEvent(WorkflowRunId runId, Row row) {
        String eventType = row.getString("event_type");
        String eventId = row.getString("event_id");
        try {
            Map<String, Object> eventData = readJsonMap(row.getValue("event_data"));
            Map<String, Object> metadata = new LinkedHashMap<>(readJsonMap(row.getValue("metadata")));

            Object nestedMetadata = eventData.get("metadata");
            if (nestedMetadata instanceof Map<?, ?> nestedMap) {
                metadata.putAll(copyMap(nestedMap));
            }
            copyEventMetadata(metadata, eventData);
            copyRowTenantMetadata(metadata, row);
            Map<String, Object> payload = eventPayload(eventData);
            if (!payload.isEmpty()) {
                metadata.put(ExecutionHistory.DOMAIN_EVENT_PAYLOAD_METADATA_KEY, payload);
            }
            metadata.putIfAbsent("source", "postgres-execution-history");

            return new GenericExecutionEvent(
                    eventId,
                    runId,
                    eventType,
                    stringValue(eventData.get("message"), eventType),
                    readInstant(row, "occurred_at"),
                    metadata);
        } catch (RuntimeException error) {
            throw new IllegalStateException(
                    "Failed to load execution history event %s of type %s for run %s"
                            .formatted(eventId, eventType, runId.value()),
                    error);
        }
    }

    private void copyRowTenantMetadata(Map<String, Object> metadata, Row row) {
        Object tenantId = rowValue(row, "tenant_id");
        String text = tenantId != null ? String.valueOf(tenantId) : "";
        if (!text.isBlank()) {
            metadata.putIfAbsent("tenantId", text);
        }
    }

    private Map<String, Object> eventPayload(Map<String, Object> eventData) {
        for (String key : List.of("output", "inputs", "outputs", "resumeData", "error")) {
            Map<String, Object> payload = mapValue(eventData.get(key));
            if (!payload.isEmpty()) {
                return payload;
            }
        }
        Object message = eventData.get("message");
        return message != null ? Map.of("message", String.valueOf(message)) : Map.of();
    }

    private void copyEventMetadata(Map<String, Object> metadata, Map<String, Object> eventData) {
        List.of(
                "nodeId",
                "attempt",
                "tenantId",
                "definitionId",
                "workflowVersion",
                "definitionVersion",
                "waitingOnNodeId",
                "humanTaskId",
                "reason",
                "willRetry",
                "retryAt",
                "nodesToCompensate",
                "compensatedNodes")
                .forEach(key -> {
                    if (eventData.containsKey(key) && eventData.get(key) != null) {
                        metadata.putIfAbsent(key, eventData.get(key));
                    }
                });
    }

    private Uni<Boolean> exists(String sql, Tuple parameters) {
        return pgPool.preparedQuery(sql)
                .execute(parameters)
                .map(rows -> {
                    var iterator = rows.iterator();
                    return iterator.hasNext() && Boolean.TRUE.equals(iterator.next().getBoolean(0));
                });
    }

    private Uni<Boolean> inserted(String sql, Tuple parameters) {
        return pgPool.preparedQuery(sql)
                .execute(parameters)
                .map(RowSet::iterator)
                .map(iterator -> iterator.hasNext());
    }

    private String tenantValue(TenantId tenantId) {
        return tenantId != null ? tenantId.value() : "system";
    }

    private String writeJson(Object value) throws JsonProcessingException {
        return objectMapper.writeValueAsString(value);
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
                throw new IllegalStateException("Invalid execution history JSON payload", error);
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

    private Object rowValue(Row row, String column) {
        return row.getColumnIndex(column) >= 0 ? row.getValue(column) : null;
    }

    private Map<String, Object> mapValue(Object value) {
        return value instanceof Map<?, ?> map ? copyMap(map) : Map.of();
    }

    private String stringValue(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String string = String.valueOf(value);
        return string.isBlank() ? fallback : string;
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
}
