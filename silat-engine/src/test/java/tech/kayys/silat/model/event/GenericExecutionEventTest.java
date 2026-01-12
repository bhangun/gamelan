package tech.kayys.silat.model.event;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tech.kayys.silat.model.WorkflowRunId;

class GenericExecutionEventTest {

    @Test
    void testGenericExecutionEventCreation() {
        WorkflowRunId runId = WorkflowRunId.of("test-run-123");
        String eventType = "TEST_EVENT";
        String message = "Test message";
        Instant occurredAt = Instant.now();
        Map<String, Object> metadata = Map.of("key1", "value1", "key2", 123);

        GenericExecutionEvent event = new GenericExecutionEvent(
            runId, eventType, message, occurredAt, metadata);

        assertEquals(runId, event.runId());
        assertEquals(eventType, event.eventType());
        assertEquals(message, event.message());
        assertEquals(occurredAt, event.occurredAt());
        assertEquals(metadata, event.metadata());
        assertNotNull(event.eventId());
        assertTrue(event.eventId().length() > 0);
    }

    @Test
    void testGenericExecutionEventWithConstructor() {
        String eventType = "ANOTHER_EVENT";
        String message = "Another test message";
        Instant occurredAt = Instant.now();
        Map<String, Object> metadata = Map.of("test", "data");

        GenericExecutionEvent event = new GenericExecutionEvent(
            eventType, message, occurredAt, metadata);

        assertEquals(eventType, event.eventType());
        assertEquals(message, event.message());
        assertEquals(occurredAt, event.occurredAt());
        assertEquals(metadata, event.metadata());
        assertNotNull(event.eventId());
        assertTrue(event.eventId().length() > 0);
        assertNull(event.runId()); // runId should be null when using this constructor
    }
}