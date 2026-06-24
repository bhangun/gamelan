package tech.kayys.gamelan.engine.node;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class InputDefinitionTest {

    @Test
    @SuppressWarnings("unchecked")
    void constructorDefensivelyCopiesAndFreezesDefaultValue() {
        Map<String, Object> nested = new HashMap<>();
        nested.put("role", "coding-agent");
        Map<String, Object> defaultValue = new HashMap<>();
        defaultValue.put("agent", nested);

        InputDefinition input = new InputDefinition(
                "profile",
                "object",
                false,
                defaultValue,
                "Agent profile");

        defaultValue.put("late", "ignored");
        nested.put("role", "mutated");

        Map<String, Object> copiedDefault = (Map<String, Object>) input.defaultValue();
        Map<String, Object> copiedAgent = (Map<String, Object>) copiedDefault.get("agent");
        assertEquals("coding-agent", copiedAgent.get("role"));
        assertThrows(UnsupportedOperationException.class, () -> copiedDefault.put("x", "y"));
        assertThrows(UnsupportedOperationException.class, () -> copiedAgent.put("x", "y"));
    }
}
