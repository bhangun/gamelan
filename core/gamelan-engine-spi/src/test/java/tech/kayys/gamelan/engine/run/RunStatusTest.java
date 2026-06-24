package tech.kayys.gamelan.engine.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class RunStatusTest {

    @Test
    void activeStates_includeEveryNonTerminalStartedRun() {
        assertFalse(RunStatus.CREATED.isActive());
        assertTrue(RunStatus.PENDING.isActive());
        assertTrue(RunStatus.RUNNING.isActive());
        assertTrue(RunStatus.SUSPENDED.isActive());
        assertTrue(RunStatus.COMPENSATING.isActive());
        assertFalse(RunStatus.COMPLETED.isActive());
        assertFalse(RunStatus.FAILED.isActive());
        assertFalse(RunStatus.CANCELLED.isActive());
        assertFalse(RunStatus.COMPENSATED.isActive());
    }

    @Test
    void activeNames_matchActiveStates() {
        assertEquals(
                List.of("PENDING", "RUNNING", "SUSPENDED", "COMPENSATING"),
                RunStatus.activeNames());
    }

    @Test
    void activeStatuses_matchActiveStates() {
        assertEquals(
                List.of(RunStatus.PENDING, RunStatus.RUNNING, RunStatus.SUSPENDED, RunStatus.COMPENSATING),
                RunStatus.activeStatuses());
    }
}
