package tech.kayys.gamelan.engine.signal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tech.kayys.gamelan.engine.node.NodeId;

class SignalTest {

    @Test
    @SuppressWarnings("unchecked")
    void constructorDefensivelyCopiesAndFreezesPayload() {
        Map<String, Object> nested = new HashMap<>();
        nested.put("approval", "yes");
        Map<String, Object> payload = new HashMap<>();
        payload.put("nested", nested);

        Signal signal = new Signal("approved", NodeId.of("approval"), payload, null);

        payload.put("late", "ignored");
        nested.put("approval", "no");

        assertNotNull(signal.timestamp());
        assertEquals("yes", ((Map<String, Object>) signal.payload().get("nested")).get("approval"));
        assertThrows(UnsupportedOperationException.class, () -> signal.payload().put("x", "y"));
        assertThrows(UnsupportedOperationException.class,
                () -> ((Map<String, Object>) signal.payload().get("nested")).put("x", "y"));
    }

    @Test
    void constructorNormalizesNullPayloadToEmptyMap() {
        Signal signal = new Signal("approved", NodeId.of("approval"), null, null);

        assertNotNull(signal.timestamp());
        assertFalse(signal.payload().containsKey("anything"));
    }

    @Test
    void constructorRejectsBlankName() {
        assertThrows(IllegalArgumentException.class, () -> new Signal(null, NodeId.of("approval"), null, null));
        assertThrows(IllegalArgumentException.class, () -> new Signal("", NodeId.of("approval"), null, null));
        assertThrows(IllegalArgumentException.class, () -> new Signal("  ", NodeId.of("approval"), null, null));
    }

    @Test
    void constructorTrimsName() {
        Signal signal = new Signal(" approved ", NodeId.of("approval"), null, null);

        assertEquals("approved", signal.name());
    }

    @Test
    void constructorNormalizesBlankIdempotencyKeyToNull() {
        Signal signal = new Signal("approved", NodeId.of("approval"), null, null, "  ");

        assertNull(signal.idempotencyKey());
    }

    @Test
    void constructorTrimsIdempotencyKey() {
        Signal signal = new Signal("approved", NodeId.of("approval"), null, null, " signal-1 ");

        assertEquals("signal-1", signal.idempotencyKey());
    }
}
