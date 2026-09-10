package tech.kayys.gamelan.engine.node;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class NodeDefinitionTest {

    @Test
    @SuppressWarnings("unchecked")
    void constructorDefensivelyCopiesAndFreezesNestedConfiguration() {
        Map<String, Object> modelConfig = new HashMap<>();
        modelConfig.put("model", "local-coder");
        Map<String, Object> configuration = new HashMap<>();
        configuration.put("agent", modelConfig);

        NodeDefinition node = NodeDefinition.builder()
                .id(NodeId.of("agent-step"))
                .type(NodeType.AGENT_LOOP)
                .configuration(configuration)
                .build();

        configuration.put("late", "ignored");
        modelConfig.put("model", "mutated");

        Map<String, Object> agent = (Map<String, Object>) node.configuration().get("agent");
        assertEquals("local-coder", agent.get("model"));
        assertThrows(UnsupportedOperationException.class, () -> node.configuration().put("x", "y"));
        assertThrows(UnsupportedOperationException.class, () -> agent.put("x", "y"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void builderAddConfigDefensivelyCopiesNestedValue() {
        Map<String, Object> tool = new HashMap<>();
        tool.put("name", "filesystem");

        NodeDefinition node = NodeDefinition.builder()
                .id(NodeId.of("tool-step"))
                .type(NodeType.AGENT_LOOP)
                .addConfig("tool", tool)
                .build();

        tool.put("name", "mutated");

        Map<String, Object> copiedTool = (Map<String, Object>) node.configuration().get("tool");
        assertEquals("filesystem", copiedTool.get("name"));
        assertThrows(UnsupportedOperationException.class, () -> copiedTool.put("x", "y"));
    }
}
