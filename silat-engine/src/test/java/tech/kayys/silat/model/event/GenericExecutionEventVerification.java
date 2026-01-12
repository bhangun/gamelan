package tech.kayys.silat.model.event;

import java.time.Instant;
import java.util.Map;
import tech.kayys.silat.model.WorkflowRunId;

/**
 * Simple test class to verify GenericExecutionEvent functionality
 */
public class GenericExecutionEventVerification {
    public static void main(String[] args) {
        System.out.println("Testing GenericExecutionEvent creation...");
        
        WorkflowRunId runId = WorkflowRunId.of("test-run-123");
        String eventType = "TEST_EVENT";
        String message = "Test message";
        Instant occurredAt = Instant.now();
        Map<String, Object> metadata = Map.of("key1", "value1", "key2", 123);

        GenericExecutionEvent event = new GenericExecutionEvent(
            runId, eventType, message, occurredAt, metadata);

        // Verify the event was created successfully
        assert event.runId() != null : "runId should not be null";
        assert event.eventType().equals("TEST_EVENT") : "eventType should be TEST_EVENT";
        assert event.message().equals("Test message") : "message should be 'Test message'";
        assert event.occurredAt() != null : "occurredAt should not be null";
        assert event.metadata() != null : "metadata should not be null";
        assert event.eventId() != null && !event.eventId().isEmpty() : "eventId should not be null or empty";
        
        System.out.println("✓ GenericExecutionEvent created successfully!");
        System.out.println("  - eventId: " + event.eventId());
        System.out.println("  - runId: " + event.runId().value());
        System.out.println("  - eventType: " + event.eventType());
        System.out.println("  - message: " + event.message());
        System.out.println("  - occurredAt: " + event.occurredAt());
        System.out.println("  - metadata: " + event.metadata());
        
        // Test the other constructor
        GenericExecutionEvent event2 = new GenericExecutionEvent(
            "ANOTHER_EVENT", "Another message", occurredAt, Map.of("test", "value"));
        
        assert event2.eventType().equals("ANOTHER_EVENT") : "eventType should be ANOTHER_EVENT";
        assert event2.runId() == null : "runId should be null for this constructor";
        
        System.out.println("✓ Second constructor works correctly!");
        System.out.println("  - eventType: " + event2.eventType());
        System.out.println("  - runId: " + event2.runId()); // Should be null
        
        System.out.println("\nAll tests passed! GenericExecutionEvent is working correctly.");
    }
}