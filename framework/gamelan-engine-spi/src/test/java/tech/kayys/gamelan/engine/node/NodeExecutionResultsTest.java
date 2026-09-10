package tech.kayys.gamelan.engine.node;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tech.kayys.gamelan.engine.error.ErrorCode;
import tech.kayys.gamelan.engine.error.ErrorInfo;
import tech.kayys.gamelan.engine.error.GamelanException;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;

class NodeExecutionResultsTest {

    @Test
    void fromExternalBuildsValidatedTokenlessResult() {
        NodeExecutionResult result = NodeExecutionResults.fromExternal(
                "run-1",
                "node-1",
                1,
                "TASK_STATUS_COMPLETED",
                Map.of("answer", 42),
                null,
                "  ",
                Instant.EPOCH);

        assertEquals(NodeExecutionStatus.COMPLETED, result.status());
        assertEquals("run-1", result.runId().value());
        assertEquals("node-1", result.nodeId().value());
        assertEquals(42, result.output().get("answer"));
        assertNull(result.executionToken());
    }

    @Test
    void fromExternalPreservesTenantOnExecutionToken() {
        NodeExecutionResult result = NodeExecutionResults.fromExternal(
                "run-1",
                "node-1",
                1,
                "COMPLETED",
                Map.of(),
                null,
                "execution-token",
                "tenant-1",
                Instant.now().plusSeconds(60));

        assertEquals(TenantId.of("tenant-1"), result.executionToken().tenantId());
    }

    @Test
    void fromExternalRejectsCompletedResultWithError() {
        GamelanException error = assertThrows(GamelanException.class, () -> NodeExecutionResults.fromExternal(
                "run-1",
                "node-1",
                1,
                "COMPLETED",
                Map.of(),
                new ErrorInfo("ERR", "bad", "", Map.of()),
                null,
                Instant.EPOCH));

        assertEquals(ErrorCode.VALIDATION_FAILED, error.getErrorCode());
        assertEquals("Completed TaskResult cannot include error", error.getSafeMessage());
    }

    @Test
    void fromExternalRejectsFailedResultWithoutError() {
        GamelanException error = assertThrows(GamelanException.class, () -> NodeExecutionResults.fromExternal(
                "run-1",
                "node-1",
                1,
                "FAILED",
                Map.of(),
                null,
                null,
                Instant.EPOCH));

        assertEquals(ErrorCode.VALIDATION_FAILED, error.getErrorCode());
        assertEquals("Failed TaskResult must include error", error.getSafeMessage());
    }

    @Test
    void fromExternalRejectsNonResultStatus() {
        GamelanException error = assertThrows(GamelanException.class, () -> NodeExecutionResults.fromExternal(
                "run-1",
                "node-1",
                1,
                "TASK_STATUS_RUNNING",
                Map.of(),
                null,
                null,
                Instant.EPOCH));

        assertEquals(ErrorCode.VALIDATION_FAILED, error.getErrorCode());
        assertEquals("TaskResult status must be COMPLETED or FAILED: RUNNING", error.getSafeMessage());
    }

    @Test
    void validateResultForRunRejectsMismatchedRunId() {
        NodeExecutionResult result = new DefaultNodeExecutionResult(
                WorkflowRunId.of("run-2"),
                NodeId.of("node-1"),
                1,
                NodeExecutionStatus.COMPLETED,
                Map.of(),
                null,
                null);

        GamelanException error = assertThrows(
                GamelanException.class,
                () -> NodeExecutionResults.validateResultForRun(WorkflowRunId.of("run-1"), result));

        assertEquals(ErrorCode.TASK_VALIDATION_FAILED, error.getErrorCode());
        assertEquals("NodeExecutionResult runId mismatch: expected run-1 but got run-2", error.getSafeMessage());
    }

    @Test
    void validateResultForRunRejectsMissingResult() {
        GamelanException error = assertThrows(
                GamelanException.class,
                () -> NodeExecutionResults.validateResultForRun(WorkflowRunId.of("run-1"), null));

        assertEquals(ErrorCode.TASK_VALIDATION_FAILED, error.getErrorCode());
        assertEquals("NodeExecutionResult is required", error.getSafeMessage());
    }
}
