package tech.kayys.gamelan.repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.RowSet;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import tech.kayys.gamelan.engine.event.EventStore;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;
import tech.kayys.gamelan.engine.event.ExecutionEvent;
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

@ApplicationScoped
public class PostgresEventStore implements EventStore {

    private static final Logger LOG = LoggerFactory.getLogger(PostgresEventStore.class);

    @Inject
    Pool pgPool;

    @Inject
    ObjectMapper objectMapper;

    @Override
    public Uni<Void> appendEvents(
            WorkflowRunId runId,
            List<ExecutionEvent> events,
            long expectedVersion) {

        if (events.isEmpty()) {
            return Uni.createFrom().voidItem();
        }

        String sql = """
                INSERT INTO workflow_events
                (event_id, run_id, tenant_id, event_type, sequence_number, event_data, occurred_at)
                VALUES ($1, $2, $3, $4, $5, $6, $7)
                """;

        List<Uni<RowSet<Row>>> batch = new ArrayList<>();
        for (int i = 0; i < events.size(); i++) {
            ExecutionEvent event = events.get(i);
            try {
                Map<String, Object> eventData = objectMapper.convertValue(
                        event,
                        new com.fasterxml.jackson.core.type.TypeReference<>() {
                        });

                batch.add(pgPool.preparedQuery(sql)
                        .execute(Tuple.tuple()
                                .addValue(event.eventId())
                                .addValue(runId.value())
                                .addValue(extractTenantId(event))
                                .addValue(event.eventType())
                                .addValue(expectedVersion + i + 1)
                                .addValue(objectMapper.writeValueAsString(eventData))
                                .addValue(event.occurredAt())));
            } catch (Exception e) {
                return Uni.createFrom().failure(e);
            }
        }

        return Uni.join().all(batch)
                .andFailFast()
                .replaceWithVoid()
                .onFailure()
                .invoke(throwable -> LOG.error("Failed to append events for run: {}", runId.value(), throwable));
    }

    @Override
    public Uni<List<ExecutionEvent>> getEvents(WorkflowRunId runId) {
        String sql = """
                SELECT event_id, event_type, event_data, occurred_at
                FROM workflow_events
                WHERE run_id = $1
                ORDER BY sequence_number ASC
                """;

        return pgPool.preparedQuery(sql)
                .execute(Tuple.of(runId.value()))
                .map(rows -> {
                    List<ExecutionEvent> events = new ArrayList<>();
                    for (Row row : rows) {
                        try {
                            ExecutionEvent event = deserializeEvent(
                                    row.getString("event_type"),
                                    row.getString("event_data"));
                            events.add(event);
                        } catch (Exception e) {
                            LOG.error("Failed to deserialize event", e);
                        }
                    }
                    return events;
                });
    }

    @Override
    public Uni<List<ExecutionEvent>> getEventsAfterVersion(
            WorkflowRunId runId,
            long afterVersion) {

        String sql = """
                SELECT event_id, event_type, event_data, occurred_at
                FROM workflow_events
                WHERE run_id = $1 AND sequence_number > $2
                ORDER BY sequence_number ASC
                """;

        return pgPool.preparedQuery(sql)
                .execute(Tuple.of(runId.value(), afterVersion))
                .map(rows -> {
                    List<ExecutionEvent> events = new ArrayList<>();
                    for (Row row : rows) {
                        try {
                            ExecutionEvent event = deserializeEvent(
                                    row.getString("event_type"),
                                    row.getString("event_data"));
                            events.add(event);
                        } catch (Exception e) {
                            LOG.error("Failed to deserialize event", e);
                        }
                    }
                    return events;
                });
    }

    @Override
    public Uni<List<ExecutionEvent>> getEventsByType(
            WorkflowRunId runId,
            String eventType) {

        String sql = """
                SELECT event_id, event_type, event_data, occurred_at
                FROM workflow_events
                WHERE run_id = $1 AND event_type = $2
                ORDER BY sequence_number ASC
                """;

        return pgPool.preparedQuery(sql)
                .execute(Tuple.of(runId.value(), eventType))
                .map(rows -> {
                    List<ExecutionEvent> events = new ArrayList<>();
                    for (Row row : rows) {
                        try {
                            ExecutionEvent event = deserializeEvent(
                                    row.getString("event_type"),
                                    row.getString("event_data"));
                            events.add(event);
                        } catch (Exception e) {
                            LOG.error("Failed to deserialize event", e);
                        }
                    }
                    return events;
                });
    }

    // Helper methods
    private String extractTenantId(ExecutionEvent event) {
        // Extract tenant ID based on event type
        if (event instanceof WorkflowStartedEvent wse) {
            return wse.tenantId().value();
        }
        return "system"; // fallback
    }

    private ExecutionEvent deserializeEvent(String eventType, String eventData)
            throws Exception {
        // Deserialize based on event type
        return objectMapper.readValue(eventData,
                getEventClass(eventType));
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
            default -> throw new IllegalArgumentException("Unknown event type: " + eventType);
        };
    }
}
