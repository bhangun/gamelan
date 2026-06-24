package tech.kayys.gamelan.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import tech.kayys.gamelan.engine.saga.CompensationEventTypes;

class ExecutionEventTypesTest {

        @Test
        void compensationEventTypes_delegateToSharedSagaCatalog() {
                assertEquals(CompensationEventTypes.COMPENSATION_STARTED,
                                ExecutionEventTypes.COMPENSATION_STARTED);
                assertEquals(CompensationEventTypes.COMPENSATION_NODE_CLAIMED,
                                ExecutionEventTypes.COMPENSATION_NODE_CLAIMED);
                assertEquals(CompensationEventTypes.COMPENSATION_NODE_CLAIM_EXPIRED,
                                ExecutionEventTypes.COMPENSATION_NODE_CLAIM_EXPIRED);
                assertEquals(CompensationEventTypes.COMPENSATION_NODE_CLAIM_RELEASED,
                                ExecutionEventTypes.COMPENSATION_NODE_CLAIM_RELEASED);
                assertEquals(CompensationEventTypes.COMPENSATION_NODE_CLAIM_SKIPPED,
                                ExecutionEventTypes.COMPENSATION_NODE_CLAIM_SKIPPED);
                assertEquals(CompensationEventTypes.COMPENSATION_NODE_COMPLETED,
                                ExecutionEventTypes.COMPENSATION_NODE_COMPLETED);
                assertEquals(CompensationEventTypes.COMPENSATION_NODE_FAILED,
                                ExecutionEventTypes.COMPENSATION_NODE_FAILED);
                assertEquals(CompensationEventTypes.COMPENSATION_NODE_SKIPPED,
                                ExecutionEventTypes.COMPENSATION_NODE_SKIPPED);
                assertEquals(CompensationEventTypes.COMPENSATION_COMPLETED,
                                ExecutionEventTypes.COMPENSATION_COMPLETED);
                assertEquals(CompensationEventTypes.COMPENSATION_FAILED,
                                ExecutionEventTypes.COMPENSATION_FAILED);
        }
}
