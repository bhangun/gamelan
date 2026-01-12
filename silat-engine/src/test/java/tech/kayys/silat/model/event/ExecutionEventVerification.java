package tech.kayys.silat.model.event;

import java.time.Instant;
import java.util.Map;
import tech.kayys.silat.model.WorkflowRunId;

/**
 * Simple verification that GenericExecutionEvent is properly permitted by ExecutionEvent
 */
public class ExecutionEventVerification {
    public static void main(String[] args) {
        System.out.println("Verifying GenericExecutionEvent is a valid subtype of ExecutionEvent...");
        
        // This should compile without issues if GenericExecutionEvent is properly permitted
        WorkflowRunId runId = WorkflowRunId.of("test-run-123");
        ExecutionEvent event = new GenericExecutionEvent(
            runId, 
            "TEST_EVENT", 
            "Test message", 
            Instant.now(), 
            Map.of("key", "value")
        );
        
        System.out.println("✓ GenericExecutionEvent successfully assigned to ExecutionEvent reference");
        System.out.println("  - Event type: " + event.getClass().getSimpleName());
        System.out.println("  - Event ID: " + event.eventId());
        System.out.println("  - Event type: " + event.eventType());
        System.out.println("  - Run ID: " + event.runId().value());
        
        System.out.println("\nVerification complete! GenericExecutionEvent is properly permitted by ExecutionEvent.");
    }
}