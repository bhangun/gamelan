package tech.kayys.gamelan.engine.node;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tech.kayys.gamelan.engine.error.ErrorCode;
import tech.kayys.gamelan.engine.error.GamelanException;
import tech.kayys.gamelan.engine.execution.ExecutionToken;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;

class DefaultNodeExecutionResultTest {

    @Test
    void constructorRejectsNonPositiveAttempt() {
        GamelanException error = assertThrows(GamelanException.class, () -> new DefaultNodeExecutionResult(
                WorkflowRunId.of("run-1"),
                NodeId.of("node-1"),
                0,
                NodeExecutionStatus.COMPLETED,
                Map.of(),
                null,
                null));

        assertEquals(ErrorCode.VALIDATION_FAILED, error.getErrorCode());
        assertEquals("NodeExecutionResult attempt must be positive", error.getSafeMessage());
    }

    @Test
    @SuppressWarnings("unchecked")
    void constructorDefensivelyCopiesAndFreezesOutput() {
        Map<String, Object> nested = new HashMap<>();
        nested.put("answer", 42);
        Map<String, Object> output = new HashMap<>();
        output.put("nested", nested);

        DefaultNodeExecutionResult result = new DefaultNodeExecutionResult(
                WorkflowRunId.of("run-1"),
                NodeId.of("node-1"),
                1,
                NodeExecutionStatus.COMPLETED,
                output,
                null,
                null);

        output.put("late", "ignored");
        nested.put("answer", 99);

        assertEquals(42, ((Map<String, Object>) result.output().get("nested")).get("answer"));
        assertThrows(UnsupportedOperationException.class, () -> result.output().put("x", "y"));
        assertThrows(UnsupportedOperationException.class,
                () -> ((Map<String, Object>) result.output().get("nested")).put("x", "y"));
    }

    @Test
    void constructorRejectsTokenThatDoesNotMatchResultIdentity() {
        WorkflowRunId runId = WorkflowRunId.of("run-1");
        GamelanException error = assertThrows(GamelanException.class, () -> new DefaultNodeExecutionResult(
                runId,
                NodeId.of("node-1"),
                2,
                NodeExecutionStatus.COMPLETED,
                Map.of(),
                null,
                new ExecutionToken(
                        "token-1",
                        runId,
                        NodeId.of("node-1"),
                        1,
                        Instant.now().plusSeconds(60))));

        assertEquals(ErrorCode.VALIDATION_FAILED, error.getErrorCode());
        assertEquals("ExecutionToken must match result identity", error.getSafeMessage());
    }

    @Test
    void constructorRejectsNonResultStatus() {
        GamelanException error = assertThrows(GamelanException.class, () -> new DefaultNodeExecutionResult(
                WorkflowRunId.of("run-1"),
                NodeId.of("node-1"),
                1,
                NodeExecutionStatus.RUNNING,
                Map.of(),
                null,
                null));

        assertEquals(ErrorCode.VALIDATION_FAILED, error.getErrorCode());
        assertEquals("NodeExecutionResult status must be COMPLETED or FAILED: RUNNING", error.getSafeMessage());
    }

    @Test
    void constructorRejectsFailedResultWithoutError() {
        GamelanException error = assertThrows(GamelanException.class, () -> new DefaultNodeExecutionResult(
                WorkflowRunId.of("run-1"),
                NodeId.of("node-1"),
                1,
                NodeExecutionStatus.FAILED,
                Map.of(),
                null,
                null));

        assertEquals(ErrorCode.VALIDATION_FAILED, error.getErrorCode());
        assertEquals("Failed NodeExecutionResult must include error", error.getSafeMessage());
    }
}
