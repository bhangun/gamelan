package tech.kayys.gamelan.registry;

import static tech.kayys.gamelan.engine.executor.ExecutorSelectionRejectionReasons.CAPACITY_SATURATED;
import static tech.kayys.gamelan.engine.executor.ExecutorSelectionRejectionReasons.INVALID_CAPACITY_METADATA;
import static tech.kayys.gamelan.engine.executor.ExecutorSelectionRejectionReasons.NO_EXECUTOR;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import tech.kayys.gamelan.engine.executor.ExecutorPlacementRequirements;
import tech.kayys.gamelan.engine.node.NodeId;

class ExecutorSelectionReportTest {

    @Test
    void derivesPrimaryRejectionReasonFromCounts() {
        ExecutorSelectionReport report = report(Map.of(
                INVALID_CAPACITY_METADATA, 2,
                CAPACITY_SATURATED, 1));

        assertEquals(CAPACITY_SATURATED, report.primaryRejectionReason());
        assertFalse(report.hasPermanentRejection());
        assertEquals(CAPACITY_SATURATED, report.toErrorContext().get("primaryRejectionReason"));
        assertEquals(false, report.toErrorContext().get("permanentRejection"));
    }

    @Test
    void classifiesPermanentPrimaryRejection() {
        ExecutorSelectionReport report = report(Map.of(INVALID_CAPACITY_METADATA, 1));

        assertEquals(INVALID_CAPACITY_METADATA, report.primaryRejectionReason());
        assertTrue(report.hasPermanentRejection());
        assertEquals(true, report.toErrorContext().get("permanentRejection"));
    }

    @Test
    void emptyFailedSelectionReportsNoExecutorReason() {
        ExecutorSelectionReport report = report(Map.of());

        assertEquals(NO_EXECUTOR, report.primaryRejectionReason());
        assertEquals(NO_EXECUTOR, report.toErrorContext().get("primaryRejectionReason"));
        assertEquals(false, report.toErrorContext().get("permanentRejection"));
    }

    private static ExecutorSelectionReport report(Map<String, Integer> rejectionCounts) {
        return new ExecutorSelectionReport(
                ExecutorSelectionRequest.forNodeType(
                        NodeId.of("agent-node"),
                        "agent",
                        ExecutorPlacementRequirements.none()),
                Optional.empty(),
                2,
                0,
                0,
                2,
                2,
                2,
                0,
                rejectionCounts,
                Map.of());
    }
}
