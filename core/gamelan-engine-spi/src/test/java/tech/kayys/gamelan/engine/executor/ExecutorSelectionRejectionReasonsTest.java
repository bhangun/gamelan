package tech.kayys.gamelan.engine.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

class ExecutorSelectionRejectionReasonsTest {

    @Test
    void primaryReasonFallsBackWhenNoDiagnosticsExist() {
        assertEquals(
                ExecutorSelectionRejectionReasons.NO_EXECUTOR,
                ExecutorSelectionRejectionReasons.primaryReason(Map.of()));
    }

    @Test
    void primaryReasonUsesStablePriority() {
        String reason = ExecutorSelectionRejectionReasons.primaryReason(Map.of(
                ExecutorSelectionRejectionReasons.EXECUTOR_TYPE_MISMATCH, 4,
                ExecutorSelectionRejectionReasons.CAPABILITY_MISMATCH, 3,
                ExecutorSelectionRejectionReasons.CAPACITY_SATURATED, 1));

        assertEquals(ExecutorSelectionRejectionReasons.CAPACITY_SATURATED, reason);
    }

    @Test
    void primaryReasonIgnoresZeroAndNegativeCounts() {
        String reason = ExecutorSelectionRejectionReasons.primaryReason(Map.of(
                ExecutorSelectionRejectionReasons.CAPACITY_SATURATED, 0,
                ExecutorSelectionRejectionReasons.INVALID_CAPACITY_METADATA, -1,
                "custom-reason", 2));

        assertEquals(ExecutorSelectionRejectionReasons.NO_COMPATIBLE_EXECUTOR, reason);
    }

    @Test
    void permanentReasonsAreExplicitlyClassified() {
        assertTrue(ExecutorSelectionRejectionReasons.isPermanent(
                ExecutorSelectionRejectionReasons.INVALID_CAPACITY_METADATA));
        assertFalse(ExecutorSelectionRejectionReasons.isPermanent(
                ExecutorSelectionRejectionReasons.CAPACITY_SATURATED));
    }
}
