package tech.kayys.gamelan.engine.node;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import tech.kayys.gamelan.engine.error.ErrorCode;
import tech.kayys.gamelan.engine.error.GamelanException;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;

class NodeResultHandlingOutcomeTest {

    @Test
    void acceptedAndDuplicateHelpersReflectAcceptance() {
        NodeResultHandlingOutcome accepted = outcome(NodeExecutionResults.Acceptance.ACCEPT);
        NodeResultHandlingOutcome duplicate = outcome(NodeExecutionResults.Acceptance.ALREADY_APPLIED);
        NodeResultHandlingOutcome ignored = outcome(NodeExecutionResults.Acceptance.RUN_NOT_ACCEPTING_RESULTS);

        assertTrue(accepted.accepted());
        assertFalse(accepted.duplicate());
        assertFalse(accepted.ignored());
        assertFalse(duplicate.accepted());
        assertTrue(duplicate.duplicate());
        assertFalse(duplicate.ignored());
        assertFalse(ignored.accepted());
        assertFalse(ignored.duplicate());
        assertTrue(ignored.ignored());
    }

    @Test
    void constructorRejectsNonPositiveAttempt() {
        GamelanException error = assertThrows(GamelanException.class, () -> new NodeResultHandlingOutcome(
                WorkflowRunId.of("run-1"),
                TenantId.of("tenant-1"),
                NodeId.of("node-1"),
                0,
                NodeExecutionResults.Acceptance.ACCEPT,
                true,
                true,
                true,
                false));

        assertEquals(ErrorCode.VALIDATION_FAILED, error.getErrorCode());
        assertEquals("Node result attempt must be positive", error.getSafeMessage());
    }

    private static NodeResultHandlingOutcome outcome(NodeExecutionResults.Acceptance acceptance) {
        return new NodeResultHandlingOutcome(
                WorkflowRunId.of("run-1"),
                TenantId.of("tenant-1"),
                NodeId.of("node-1"),
                1,
                acceptance,
                true,
                true,
                true,
                false);
    }
}
