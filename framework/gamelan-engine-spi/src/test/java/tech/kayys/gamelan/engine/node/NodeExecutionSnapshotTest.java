package tech.kayys.gamelan.engine.node;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class NodeExecutionSnapshotTest {

    @Test
    @SuppressWarnings("unchecked")
    void constructorDefensivelyCopiesAndFreezesOutput() {
        Map<String, Object> nested = new HashMap<>();
        nested.put("answer", 42);
        Map<String, Object> output = new HashMap<>();
        output.put("nested", nested);

        NodeExecutionSnapshot snapshot = new NodeExecutionSnapshot(
                "node-1",
                NodeExecutionStatus.COMPLETED.name(),
                1,
                Instant.EPOCH,
                Instant.EPOCH.plusSeconds(1),
                Instant.EPOCH.plusSeconds(2),
                output,
                null);

        output.put("late", "ignored");
        nested.put("answer", 99);

        assertEquals(Instant.EPOCH.plusSeconds(2), snapshot.retryAt());
        assertEquals(42, ((Map<String, Object>) snapshot.output().get("nested")).get("answer"));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.output().put("x", "y"));
        assertThrows(UnsupportedOperationException.class,
                () -> ((Map<String, Object>) snapshot.output().get("nested")).put("x", "y"));
    }
}
