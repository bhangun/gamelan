package tech.kayys.gamelan.engine.saga;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;

import org.junit.jupiter.api.Test;

class CompensationEventTypesTest {

        @Test
        void compensationEventTypes_areUniqueAndScoped() {
                List<String> eventTypes = List.of(
                                CompensationEventTypes.COMPENSATION_STARTED,
                                CompensationEventTypes.COMPENSATION_NODE_CLAIMED,
                                CompensationEventTypes.COMPENSATION_NODE_CLAIM_EXPIRED,
                                CompensationEventTypes.COMPENSATION_NODE_CLAIM_RELEASED,
                                CompensationEventTypes.COMPENSATION_NODE_CLAIM_SKIPPED,
                                CompensationEventTypes.COMPENSATION_NODE_COMPLETED,
                                CompensationEventTypes.COMPENSATION_NODE_FAILED,
                                CompensationEventTypes.COMPENSATION_NODE_SKIPPED,
                                CompensationEventTypes.COMPENSATION_COMPLETED,
                                CompensationEventTypes.COMPENSATION_FAILED);

                assertEquals(eventTypes.size(), new HashSet<>(eventTypes).size());
                assertTrue(eventTypes.stream().allMatch(type -> type.startsWith("COMPENSATION_")));
        }
}
