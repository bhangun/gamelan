package tech.kayys.silat.sdk.executor.examples;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.smallrye.mutiny.helpers.test.UniAssertSubscriber;
import tech.kayys.silat.execution.NodeExecutionResult;
import tech.kayys.silat.execution.NodeExecutionStatus;
import tech.kayys.silat.execution.NodeExecutionTask;
import tech.kayys.silat.model.ExecutionToken;
import tech.kayys.silat.model.NodeId;
import tech.kayys.silat.model.WorkflowRunId;

/**
 * Example test for OrderValidatorExecutor
 * Demonstrates how to test custom executors
 */
class OrderValidatorExecutorTest {

    private OrderValidatorExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new OrderValidatorExecutor();
    }

    @Test
    void testValidOrder() {
        // Given: A valid order task
        NodeExecutionTask task = createTask(Map.of("orderId", "ORDER-12345"));

        // When: Execute the task
        UniAssertSubscriber<NodeExecutionResult> subscriber = executor.execute(task)
                .subscribe().withSubscriber(UniAssertSubscriber.create());

        // Then: Should complete successfully
        NodeExecutionResult result = subscriber.awaitItem().getItem();

        assertNotNull(result);
        assertEquals(NodeExecutionStatus.COMPLETED, result.status());

        Map<String, Object> output = result.output();
        assertNotNull(output);
        assertTrue((Boolean) output.get("valid"));
        assertNotNull(output.get("validatedAt"));
    }

    @Test
    void testInvalidOrder_NullOrderId() {
        // Given: A task with null orderId
        NodeExecutionTask task = createTask(Map.of());

        // When: Execute the task
        UniAssertSubscriber<NodeExecutionResult> subscriber = executor.execute(task)
                .subscribe().withSubscriber(UniAssertSubscriber.create());

        // Then: Should fail with validation error
        NodeExecutionResult result = subscriber.awaitItem().getItem();

        assertNotNull(result);
        assertEquals(NodeExecutionStatus.FAILED, result.status());
        assertNotNull(result.error());
        assertEquals("INVALID_ORDER", result.error().code());
    }

    @Test
    void testInvalidOrder_WrongPrefix() {
        // Given: A task with invalid order ID prefix
        NodeExecutionTask task = createTask(Map.of("orderId", "INVALID-12345"));

        // When: Execute the task
        UniAssertSubscriber<NodeExecutionResult> subscriber = executor.execute(task)
                .subscribe().withSubscriber(UniAssertSubscriber.create());

        // Then: Should fail with validation error
        NodeExecutionResult result = subscriber.awaitItem().getItem();

        assertNotNull(result);
        assertEquals(NodeExecutionStatus.FAILED, result.status());
        assertNotNull(result.error());
        assertEquals("INVALID_ORDER", result.error().code());
    }

    @Test
    void testExecutorMetadata() {
        // Verify executor configuration
        assertEquals("order-validator", executor.getExecutorType());
        assertEquals(20, executor.getMaxConcurrentTasks());
        assertTrue(executor.isReady());
    }

    @Test
    void testConcurrentExecution() {
        // Given: Multiple tasks
        NodeExecutionTask task1 = createTask(Map.of("orderId", "ORDER-001"));
        NodeExecutionTask task2 = createTask(Map.of("orderId", "ORDER-002"));
        NodeExecutionTask task3 = createTask(Map.of("orderId", "INVALID-003"));

        // When: Execute concurrently
        UniAssertSubscriber<NodeExecutionResult> sub1 = executor.execute(task1)
                .subscribe().withSubscriber(UniAssertSubscriber.create());
        UniAssertSubscriber<NodeExecutionResult> sub2 = executor.execute(task2)
                .subscribe().withSubscriber(UniAssertSubscriber.create());
        UniAssertSubscriber<NodeExecutionResult> sub3 = executor.execute(task3)
                .subscribe().withSubscriber(UniAssertSubscriber.create());

        // Then: All should complete
        NodeExecutionResult result1 = sub1.awaitItem().getItem();
        NodeExecutionResult result2 = sub2.awaitItem().getItem();
        NodeExecutionResult result3 = sub3.awaitItem().getItem();

        assertEquals(NodeExecutionStatus.COMPLETED, result1.status());
        assertEquals(NodeExecutionStatus.COMPLETED, result2.status());
        assertEquals(NodeExecutionStatus.FAILED, result3.status());
    }

    /**
     * Helper method to create a test task
     */
    private NodeExecutionTask createTask(Map<String, Object> context) {
        WorkflowRunId runId = WorkflowRunId.generate();
        NodeId nodeId = NodeId.of("test-node-" + UUID.randomUUID());
        int attempt = 1;
        ExecutionToken token = ExecutionToken.create(runId, nodeId, attempt, Duration.ofMinutes(10));

        return new NodeExecutionTask(
                runId,
                nodeId,
                attempt,
                token,
                context,
                null // retryPolicy - not needed for tests
        );
    }
}
