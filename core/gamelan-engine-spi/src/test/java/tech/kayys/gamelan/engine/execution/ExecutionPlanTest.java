package tech.kayys.gamelan.engine.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tech.kayys.gamelan.engine.node.NodeId;

class ExecutionPlanTest {

    @Test
    void constructor_defaultsNullCollectionsToEmptyImmutableCollections() {
        ExecutionPlan plan = new ExecutionPlan(null, false, false, null);

        assertTrue(plan.readyNodes().isEmpty());
        assertTrue(plan.outputs().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> plan.readyNodes().add(NodeId.of("next")));
        assertThrows(UnsupportedOperationException.class, () -> plan.outputs().put("value", 1));
    }

    @Test
    void constructor_defensivelyCopiesReadyNodesAndOutputs() {
        List<NodeId> readyNodes = new ArrayList<>(List.of(NodeId.of("first")));
        Map<String, Object> nestedOutput = new LinkedHashMap<>();
        nestedOutput.put("status", "ok");
        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("result", nestedOutput);

        ExecutionPlan plan = new ExecutionPlan(readyNodes, false, false, outputs);
        readyNodes.add(NodeId.of("second"));
        nestedOutput.put("status", "mutated");

        assertEquals(List.of(NodeId.of("first")), plan.readyNodes());
        assertEquals("ok", ((Map<?, ?>) plan.outputs().get("result")).get("status"));
        assertThrows(UnsupportedOperationException.class, () -> plan.readyNodes().clear());
        assertThrows(UnsupportedOperationException.class, () -> plan.outputs().clear());
    }
}
