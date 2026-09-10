package tech.kayys.gamelan.sdk.executor.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tech.kayys.gamelan.engine.error.ErrorCode;
import tech.kayys.gamelan.engine.error.ErrorInfo;
import tech.kayys.gamelan.engine.error.GamelanException;
import tech.kayys.gamelan.engine.execution.ExecutionToken;
import tech.kayys.gamelan.engine.node.NodeExecutionResult;
import tech.kayys.gamelan.engine.node.NodeExecutionStatus;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;

class SimpleNodeExecutionResultTest {

    private static final WorkflowRunId RUN_ID = WorkflowRunId.of("run-1");
    private static final NodeId NODE_ID = NodeId.of("node-1");

    @Test
    @SuppressWarnings("unchecked")
    void successDefensivelyCopiesAndFreezesOutput() {
        Map<String, Object> nested = new HashMap<>();
        nested.put("answer", 42);
        Map<String, Object> output = new HashMap<>();
        output.put("nested", nested);

        NodeExecutionResult result = SimpleNodeExecutionResult.success(
                RUN_ID,
                NODE_ID,
                1,
                output,
                token(),
                Duration.ZERO);

        output.put("late", "ignored");
        nested.put("answer", 99);

        assertEquals(42, ((Map<String, Object>) result.output().get("nested")).get("answer"));
        assertThrows(UnsupportedOperationException.class, () -> result.output().put("x", "y"));
        assertThrows(UnsupportedOperationException.class,
                () -> ((Map<String, Object>) result.output().get("nested")).put("x", "y"));
    }

    @Test
    void constructorRejectsNonPositiveAttempt() {
        GamelanException error = assertThrows(GamelanException.class, () -> new SimpleNodeExecutionResult(
                RUN_ID,
                NODE_ID,
                0,
                NodeExecutionStatus.COMPLETED,
                Map.of(),
                null,
                null,
                Instant.now(),
                Duration.ZERO,
                null,
                null,
                Map.of()));

        assertEquals(ErrorCode.VALIDATION_FAILED, error.getErrorCode());
        assertEquals("NodeExecutionResult attempt must be positive", error.getSafeMessage());
    }

    @Test
    void constructorRejectsTokenThatDoesNotMatchResultIdentity() {
        GamelanException error = assertThrows(GamelanException.class, () -> new SimpleNodeExecutionResult(
                RUN_ID,
                NODE_ID,
                2,
                NodeExecutionStatus.COMPLETED,
                Map.of(),
                null,
                token(1),
                Instant.now(),
                Duration.ZERO,
                null,
                null,
                Map.of()));

        assertEquals(ErrorCode.VALIDATION_FAILED, error.getErrorCode());
        assertEquals("ExecutionToken must match result identity", error.getSafeMessage());
    }

    @Test
    void constructorRejectsNonResultStatus() {
        GamelanException error = assertThrows(GamelanException.class, () -> new SimpleNodeExecutionResult(
                RUN_ID,
                NODE_ID,
                1,
                NodeExecutionStatus.RETRYING,
                Map.of(),
                null,
                null,
                Instant.now(),
                Duration.ZERO,
                null,
                null,
                Map.of()));

        assertEquals(ErrorCode.VALIDATION_FAILED, error.getErrorCode());
        assertEquals("NodeExecutionResult status must be COMPLETED or FAILED: RETRYING", error.getSafeMessage());
    }

    @Test
    void constructorRejectsCompletedResultWithError() {
        GamelanException error = assertThrows(GamelanException.class, () -> new SimpleNodeExecutionResult(
                RUN_ID,
                NODE_ID,
                1,
                NodeExecutionStatus.COMPLETED,
                Map.of(),
                ErrorInfo.of(new IllegalStateException("should not be here")),
                null,
                Instant.now(),
                Duration.ZERO,
                null,
                null,
                Map.of()));

        assertEquals(ErrorCode.VALIDATION_FAILED, error.getErrorCode());
        assertEquals("Completed NodeExecutionResult cannot include error", error.getSafeMessage());
    }

    private static ExecutionToken token() {
        return token(1);
    }

    private static ExecutionToken token(int attempt) {
        return ExecutionToken.create(RUN_ID, NODE_ID, attempt, Duration.ofMinutes(1));
    }
}
