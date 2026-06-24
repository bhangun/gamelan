package tech.kayys.gamelan.repository;

import java.time.Instant;
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

import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.SqlClient;
import io.vertx.mutiny.sqlclient.SqlConnection;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import tech.kayys.gamelan.engine.event.CompensationCompletedEvent;
import tech.kayys.gamelan.engine.event.CompensationFailedEvent;
import tech.kayys.gamelan.engine.event.CompensationStartedEvent;
import tech.kayys.gamelan.engine.event.EventStreamVersionConflictException;
import tech.kayys.gamelan.engine.event.EventStore;
import tech.kayys.gamelan.engine.event.ExecutionEvent;
import tech.kayys.gamelan.engine.event.GenericExecutionEvent;
import tech.kayys.gamelan.engine.event.NodeCompletedEvent;
import tech.kayys.gamelan.engine.event.NodeFailedEvent;
import tech.kayys.gamelan.engine.event.NodeScheduledEvent;
import tech.kayys.gamelan.engine.event.NodeStartedEvent;
import tech.kayys.gamelan.engine.event.WorkflowCancelledEvent;
import tech.kayys.gamelan.engine.event.WorkflowCompletedEvent;
import tech.kayys.gamelan.engine.event.WorkflowFailedEvent;
import tech.kayys.gamelan.engine.event.WorkflowResumedEvent;
import tech.kayys.gamelan.engine.event.WorkflowStartedEvent;
import tech.kayys.gamelan.engine.event.WorkflowSuspendedEvent;
import tech.kayys.gamelan.engine.execution.ExecutionEventEnvelopes;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;

@ApplicationScoped
@io.quarkus.arc.properties.IfBuildProperty(name = "quarkus.datasource.db-kind", stringValue = "postgresql")
public class PostgresEventStore implements EventStore {

    private static final Logger LOG = LoggerFactory.getLogger(PostgresEventStore.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    @Inject
    Pool pgPool;

    @Inject
    ObjectMapper objectMapper;

    @Override
    public Uni<Void> appendEvents(
            WorkflowRunId runId,
            List<ExecutionEvent> events,
            long expectedVersion) {
        return appendEvents(runId, inferTenantId(events), events, expectedVersion);
    }

    @Override
    public Uni<Void> appendEvents(
            WorkflowRunId runId,
            TenantId tenantId,
            List<ExecutionEvent> events,
            long expectedVersion) {

        List<ExecutionEvent> safeEvents;
        List<EventAppendRecord> eventBatch;
        try {
            safeEvents = ExecutionEventEnvelopes.validateForRun(runId, tenantId, events);
            eventBatch = prepareEvents(safeEvents);
        } catch (IllegalArgumentException | JsonProcessingException error) {
            return Uni.createFrom().failure(error);
        }
        if (eventBatch.isEmpty()) {
            return Uni.createFrom().voidItem();
        }

        return pgPool.withTransaction(connection -> appendEventsInTransaction(
                connection,
                runId,
                tenantId,
                eventBatch,
                expectedVersion))
                .onFailure()
                .invoke(throwable -> logAppendFailure(runId, tenantId, throwable));
    }

    private void logAppendFailure(WorkflowRunId runId, TenantId tenantId, Throwable throwable) {
        if (throwable instanceof EventStreamVersionConflictException conflict) {
            LOG.debug(
                    "Event stream version conflict for run: {} tenant: {} expectedVersion: {}",
                    runId.value(),
                    tenantValue(tenantId),
                    conflict.expectedVersion());
            return;
        }
        LOG.error("Failed to append events for run: {}", runId.value(), throwable);
    }

    private Uni<Void> appendEventsInTransaction(
            SqlConnection connection,
            WorkflowRunId runId,
            TenantId tenantId,
            List<EventAppendRecord> events,
            long expectedVersion) {
        Uni<Void> chain = Uni.createFrom().voidItem();
        long previousVersion = expectedVersion;
        for (EventAppendRecord event : events) {
            long expectedEventVersion = previousVersion;
            long nextVersion = expectedEventVersion + 1;
            chain = chain.chain(() -> appendEvent(connection, runId, tenantId, event, expectedEventVersion, nextVersion));
            previousVersion = nextVersion;
        }
        return chain;
    }

    @Override
    public Uni<List<ExecutionEvent>> getEvents(WorkflowRunId runId) {
        String sql = """
                SELECT event_id, event_type, event_data, occurred_at
                FROM workflow_events
                WHERE run_id = $1
                AND tenant_id = 'system'
                ORDER BY sequence_number ASC
                """;

        return loadEvents(sql, Tuple.of(runId.value()), runId);
    }

    @Override
    public Uni<List<ExecutionEvent>> getEvents(WorkflowRunId runId, TenantId tenantId) {
        if (tenantId == null) {
            return getEvents(runId);
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
                SELECT event_id, event_type, event_data, occurred_at
                FROM workflow_events, tenant_stream
                WHERE run_id = $1
                AND (
                    tenant_id = $2
                    OR (tenant_id = 'system' AND NOT tenant_stream.has_tenant_events)
                )
                ORDER BY sequence_number ASC
                """;

        return loadEvents(sql, Tuple.of(runId.value(), tenantId.value()), runId, tenantId);
    }

    @Override
    public Uni<List<ExecutionEvent>> getEventsAfterVersion(
            WorkflowRunId runId,
            long afterVersion) {

        String sql = """
                SELECT event_id, event_type, event_data, occurred_at
                FROM workflow_events
                WHERE run_id = $1
                AND tenant_id = 'system'
                AND sequence_number > $2
                ORDER BY sequence_number ASC
                """;

        return loadEvents(sql, Tuple.of(runId.value(), afterVersion), runId);
    }

    @Override
    public Uni<List<ExecutionEvent>> getEventsAfterVersion(
            WorkflowRunId runId,
            TenantId tenantId,
            long afterVersion) {
        if (tenantId == null) {
            return getEventsAfterVersion(runId, afterVersion);
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
                SELECT event_id, event_type, event_data, occurred_at
                FROM workflow_events, tenant_stream
                WHERE run_id = $1
                AND (
                    tenant_id = $2
                    OR (tenant_id = 'system' AND NOT tenant_stream.has_tenant_events)
                )
                AND sequence_number > $3
                ORDER BY sequence_number ASC
                """;

        return loadEvents(sql, Tuple.of(runId.value(), tenantId.value(), afterVersion), runId, tenantId);
    }

    @Override
    public Uni<List<ExecutionEvent>> getEventsByType(
            WorkflowRunId runId,
            String eventType) {

        String sql = """
                SELECT event_id, event_type, event_data, occurred_at
                FROM workflow_events
                WHERE run_id = $1
                AND tenant_id = 'system'
                AND event_type = $2
                ORDER BY sequence_number ASC
                """;

        return loadEvents(sql, Tuple.of(runId.value(), eventType), runId);
    }

    @Override
    public Uni<List<ExecutionEvent>> getEventsByType(
            WorkflowRunId runId,
            TenantId tenantId,
            String eventType) {
        if (tenantId == null) {
            return getEventsByType(runId, eventType);
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
                SELECT event_id, event_type, event_data, occurred_at
                FROM workflow_events, tenant_stream
                WHERE run_id = $1
                AND (
                    tenant_id = $2
                    OR (tenant_id = 'system' AND NOT tenant_stream.has_tenant_events)
                )
                AND event_type = $3
                ORDER BY sequence_number ASC
                """;

        return loadEvents(sql, Tuple.of(runId.value(), tenantId.value(), eventType), runId, tenantId);
    }

    private Uni<Void> appendEvent(
            SqlClient client,
            WorkflowRunId runId,
            TenantId tenantId,
            EventAppendRecord event,
            long expectedVersion,
            long sequenceNumber) {
        String tenantValue = tenantValue(tenantId);
        String sql = """
                WITH run_lock AS (
                    SELECT pg_advisory_xact_lock(hashtext($3 || ':' || $2)::bigint) AS locked
                ),
                current_version AS (
                    SELECT COALESCE(MAX(workflow_events.sequence_number), 0) AS sequence_number
                    FROM run_lock
                    LEFT JOIN workflow_events ON workflow_events.run_id = $2
                    AND workflow_events.tenant_id = $3
                )
                INSERT INTO workflow_events
                (event_id, run_id, tenant_id, event_type, sequence_number, event_data, occurred_at, metadata)
                SELECT $1, $2, $3, $4, $9, $5::jsonb, $6, $7::jsonb
                FROM current_version
                WHERE current_version.sequence_number = $8
                RETURNING 1
                """;

        return inserted(client, sql, Tuple.tuple()
                .addValue(event.eventId())
                .addValue(runId.value())
                .addValue(tenantValue)
                .addValue(event.eventType())
                .addValue(event.eventDataJson())
                .addValue(event.occurredAt())
                .addValue(event.metadataJson())
                .addValue(expectedVersion)
                .addValue(sequenceNumber))
                .chain(inserted -> inserted
                        ? Uni.createFrom().voidItem()
                        : Uni.createFrom().failure(new EventStreamVersionConflictException(
                                runId,
                                tenantId,
                                expectedVersion)));
    }

    private List<EventAppendRecord> prepareEvents(List<ExecutionEvent> events)
            throws JsonProcessingException {
        List<EventAppendRecord> prepared = new ArrayList<>(events.size());
        for (ExecutionEvent event : events) {
            prepared.add(prepareEvent(event));
        }
        return prepared;
    }

    private EventAppendRecord prepareEvent(ExecutionEvent event)
            throws JsonProcessingException {
        Map<String, Object> eventData = objectMapper.convertValue(event, MAP_TYPE);
        String eventType = ExecutionEventEnvelopes.safeEventType(event);
        Map<String, Object> metadata = Map.of(
                "source", "event-store",
                "domainEventType", eventType);

        return new EventAppendRecord(
                event.eventId() != null ? event.eventId() : UUID.randomUUID().toString(),
                eventType,
                objectMapper.writeValueAsString(eventData),
                event.occurredAt() != null ? event.occurredAt() : Instant.now(),
                objectMapper.writeValueAsString(metadata));
    }

    private Uni<List<ExecutionEvent>> loadEvents(String sql, Tuple parameters, WorkflowRunId runId) {
        return loadEvents(sql, parameters, runId, null);
    }

    private Uni<List<ExecutionEvent>> loadEvents(
            String sql,
            Tuple parameters,
            WorkflowRunId runId,
            TenantId tenantId) {
        return pgPool.preparedQuery(sql)
                .execute(parameters)
                .map(rows -> {
                    List<ExecutionEvent> events = new ArrayList<>();
                    for (Row row : rows) {
                        events.add(deserializeRow(row));
                    }
                    return ExecutionEventEnvelopes.validateForRun(runId, tenantId, events);
                });
    }

    private ExecutionEvent deserializeRow(Row row) {
        String eventId = row.getString("event_id");
        String eventType = row.getString("event_type");
        try {
            return deserializeEvent(eventType, row.getValue("event_data"));
        } catch (Exception error) {
            throw new IllegalStateException(
                    "Failed to deserialize workflow event %s of type %s".formatted(eventId, eventType),
                    error);
        }
    }

    private Uni<Boolean> inserted(SqlClient client, String sql, Tuple parameters) {
        return client.preparedQuery(sql)
                .execute(parameters)
                .map(rows -> rows.rowCount() == 1 || rows.iterator().hasNext());
    }

    private TenantId inferTenantId(List<ExecutionEvent> events) {
        if (events == null) {
            return null;
        }
        for (ExecutionEvent event : events) {
            TenantId tenantId = extractTenantId(event);
            if (tenantId != null) {
                return tenantId;
            }
        }
        return null;
    }

    private TenantId extractTenantId(ExecutionEvent event) {
        if (event instanceof WorkflowStartedEvent started) {
            return started.tenantId();
        }
        if (event instanceof CompensationStartedEvent started) {
            return started.tenantId();
        }
        if (event instanceof CompensationCompletedEvent completed) {
            return completed.tenantId();
        }
        if (event instanceof CompensationFailedEvent failed) {
            return failed.tenantId();
        }
        if (event instanceof GenericExecutionEvent generic) {
            return tenantIdFromMetadata(generic.metadata().get("tenantId"));
        }
        return null;
    }

    private TenantId tenantIdFromMetadata(Object value) {
        if (value instanceof TenantId tenantId) {
            return tenantId;
        }
        if (value instanceof String tenantId && !tenantId.isBlank()) {
            return TenantId.of(tenantId);
        }
        return null;
    }

    private String tenantValue(TenantId tenantId) {
        return tenantId != null ? tenantId.value() : "system";
    }

    private ExecutionEvent deserializeEvent(String eventType, String eventData)
            throws Exception {
        return deserializeEvent(eventType, (Object) eventData);
    }

    private ExecutionEvent deserializeEvent(String eventType, Object eventData)
            throws Exception {
        if (eventData == null) {
            throw new IllegalArgumentException("event_data cannot be null");
        }
        Class<? extends ExecutionEvent> eventClass = getEventClass(eventType);
        if (eventData instanceof String text) {
            return objectMapper.readValue(text, eventClass);
        }
        if (eventData instanceof JsonObject jsonObject) {
            return objectMapper.convertValue(jsonObject.getMap(), eventClass);
        }
        if (eventData instanceof Map<?, ?> map) {
            return objectMapper.convertValue(copyMap(map), eventClass);
        }
        return objectMapper.convertValue(eventData, eventClass);
    }

    private Map<String, Object> copyMap(Map<?, ?> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key != null) {
                copy.put(String.valueOf(key), value);
            }
        });
        return copy;
    }

    private Class<? extends ExecutionEvent> getEventClass(String eventType) {
        return switch (eventType) {
            case "WorkflowStarted" -> WorkflowStartedEvent.class;
            case "NodeScheduled" -> NodeScheduledEvent.class;
            case "NodeStarted" -> NodeStartedEvent.class;
            case "NodeCompleted" -> NodeCompletedEvent.class;
            case "NodeFailed" -> NodeFailedEvent.class;
            case "WorkflowSuspended" -> WorkflowSuspendedEvent.class;
            case "WorkflowResumed" -> WorkflowResumedEvent.class;
            case "WorkflowCompleted" -> WorkflowCompletedEvent.class;
            case "WorkflowFailed" -> WorkflowFailedEvent.class;
            case "WorkflowCancelled" -> WorkflowCancelledEvent.class;
            case "CompensationStarted" -> CompensationStartedEvent.class;
            case "CompensationCompleted" -> CompensationCompletedEvent.class;
            case "CompensationFailed" -> CompensationFailedEvent.class;
            default -> GenericExecutionEvent.class;
        };
    }

    private record EventAppendRecord(
            String eventId,
            String eventType,
            String eventDataJson,
            Instant occurredAt,
            String metadataJson) {
    }
}
