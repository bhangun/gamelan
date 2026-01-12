package tech.kayys.silat.model.event;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import tech.kayys.silat.model.WorkflowRunId;

/**
 * Generic execution event for storing arbitrary events with type, message, and metadata.
 * Used when a specific event type is not available or needed.
 */
public record GenericExecutionEvent(
        String eventId,
        WorkflowRunId runId,
        String eventType,
        String message,
        Instant occurredAt,
        Map<String, Object> metadata) implements ExecutionEvent {

    public GenericExecutionEvent(
            String eventType,
            String message,
            Instant occurredAt,
            Map<String, Object> metadata) {
        this(
            UUID.randomUUID().toString(),
            null, // runId will be set when adding to repository
            eventType,
            message,
            occurredAt,
            metadata
        );
    }

    public GenericExecutionEvent(
            WorkflowRunId runId,
            String eventType,
            String message,
            Instant occurredAt,
            Map<String, Object> metadata) {
        this(
            UUID.randomUUID().toString(),
            runId,
            eventType,
            message,
            occurredAt,
            metadata
        );
    }

    @Override
    public String eventType() {
        return this.eventType;
    }
}