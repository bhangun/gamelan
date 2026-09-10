package tech.kayys.gamelan.engine.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ExecutorSelectionPolicyTest {

    @Test
    void fromContextParsesNestedSelectionPolicy() {
        ExecutorSelectionPolicy policy = ExecutorSelectionPolicy.fromContext(Map.of(
                ExecutorSelectionPolicy.CONTEXT_KEY,
                Map.of(
                        ExecutorSelectionPolicy.CONTEXT_STRATEGY_KEY, " weighted ",
                        ExecutorSelectionPolicy.CONTEXT_REQUIRED_CAPABILITIES_KEY,
                        java.util.List.of("coding", "sandbox"),
                        ExecutorSelectionPolicy.CONTEXT_PREFERRED_CAPABILITIES_KEY,
                        java.util.List.of("browser"),
                        ExecutorSelectionPolicy.CONTEXT_EXCLUDED_CAPABILITIES_KEY,
                        java.util.List.of("finance"),
                        ExecutorSelectionPolicy.CONTEXT_MIN_MEMORY_MB_KEY, 4096,
                        ExecutorSelectionPolicy.CONTEXT_MIN_CPU_CORES_KEY, "2.5",
                        ExecutorSelectionPolicy.CONTEXT_REGIONS_KEY, java.util.List.of("us-east-1"),
                        ExecutorSelectionPolicy.CONTEXT_DATA_RESIDENCIES_KEY, java.util.List.of("us"),
                        "pool", "agentic-local")));

        assertEquals("weighted", policy.strategy());
        assertEquals(java.util.Set.of("coding", "sandbox"), policy.requiredCapabilities());
        assertEquals(java.util.Set.of("browser"), policy.preferredCapabilities());
        assertEquals(java.util.Set.of("finance"), policy.excludedCapabilities());
        assertEquals(4096L, policy.resourceRequirements().minMemoryMb());
        assertEquals(2.5D, policy.resourceRequirements().minCpuCores());
        assertEquals(java.util.Set.of("us-east-1"), policy.resourceRequirements().regions());
        assertEquals(java.util.Set.of("us"), policy.resourceRequirements().dataResidencies());
        assertEquals("agentic-local", policy.context().get("pool"));
        assertEquals("weighted", policy.toSelectionContext().get(
                ExecutorSelectionPolicy.CONTEXT_SELECTION_STRATEGY_KEY));
        assertEquals(
                java.util.Set.of("coding", "sandbox"),
                java.util.Set.copyOf((java.util.List<?>) policy.toSelectionContext().get(
                        ExecutorSelectionPolicy.CONTEXT_REQUIRED_CAPABILITIES_KEY)));
        assertEquals(
                java.util.Set.of("browser"),
                java.util.Set.copyOf((java.util.List<?>) policy.toSelectionContext().get(
                        ExecutorSelectionPolicy.CONTEXT_PREFERRED_CAPABILITIES_KEY)));
        assertEquals(
                java.util.Set.of("finance"),
                java.util.Set.copyOf((java.util.List<?>) policy.toSelectionContext().get(
                        ExecutorSelectionPolicy.CONTEXT_EXCLUDED_CAPABILITIES_KEY)));
        assertEquals(4096L, policy.toSelectionContext().get(ExecutorSelectionPolicy.CONTEXT_MIN_MEMORY_MB_KEY));
        assertEquals(2.5D, policy.toSelectionContext().get(ExecutorSelectionPolicy.CONTEXT_MIN_CPU_CORES_KEY));
    }

    @Test
    void explicitStrategyKeyOverridesNestedStrategy() {
        ExecutorSelectionPolicy policy = ExecutorSelectionPolicy.fromContext(Map.of(
                ExecutorSelectionPolicy.CONTEXT_KEY,
                Map.of(ExecutorSelectionPolicy.CONTEXT_STRATEGY_KEY, "round-robin"),
                ExecutorSelectionPolicy.STRATEGY_KEY,
                "random"));

        assertEquals("random", policy.strategy());
        assertEquals("random", policy.toSelectionContext().get(
                ExecutorSelectionPolicy.CONTEXT_SELECTION_STRATEGY_KEY));
    }

    @Test
    void stringSelectionIsStrategyShortcut() {
        ExecutorSelectionPolicy policy = ExecutorSelectionPolicy.fromContext(Map.of(
                ExecutorSelectionPolicy.CONTEXT_KEY,
                "weighted"));

        assertEquals("weighted", policy.strategy());
        assertEquals("weighted", policy.toSelectionContext().get(
                ExecutorSelectionPolicy.CONTEXT_SELECTION_STRATEGY_KEY));
    }

    @Test
    void parsesCapabilityString() {
        ExecutorSelectionPolicy policy = ExecutorSelectionPolicy.fromContext(Map.of(
                ExecutorSelectionPolicy.CONTEXT_KEY,
                Map.of(ExecutorSelectionPolicy.CONTEXT_CAPABILITIES_KEY, " Coding;SANDBOX research ")));

        assertEquals(java.util.Set.of("coding", "sandbox", "research"), policy.requiredCapabilities());
        assertTrue(policy.hasRequiredCapabilities());
    }

    @Test
    void explicitCapabilitiesAreNormalized() {
        ExecutorSelectionPolicy policy = new ExecutorSelectionPolicy(
                null,
                java.util.Set.of(" Coding ", "SANDBOX"),
                Map.of());

        assertEquals(java.util.Set.of("coding", "sandbox"), policy.requiredCapabilities());
    }

    @Test
    void validationErrorsRejectImpossibleCapabilityCombinations() {
        ExecutorSelectionPolicy policy = ExecutorSelectionPolicy.fromContext(Map.of(
                ExecutorSelectionPolicy.CONTEXT_KEY,
                Map.of(
                        ExecutorSelectionPolicy.CONTEXT_REQUIRED_CAPABILITIES_KEY, java.util.List.of("coding"),
                        ExecutorSelectionPolicy.CONTEXT_PREFERRED_CAPABILITIES_KEY, java.util.List.of("browser"),
                        ExecutorSelectionPolicy.CONTEXT_EXCLUDED_CAPABILITIES_KEY,
                        java.util.List.of("coding", "browser"))));

        assertEquals(
                java.util.List.of(
                        "required and excluded capabilities overlap: [coding]",
                        "preferred and excluded capabilities overlap: [browser]"),
                policy.validationErrors());
    }

    @Test
    void fromContextRejectsUnsupportedSelectionShape() {
        tech.kayys.gamelan.engine.error.GamelanException error = assertThrows(
                tech.kayys.gamelan.engine.error.GamelanException.class,
                () -> ExecutorSelectionPolicy.fromContext(Map.of(
                        ExecutorSelectionPolicy.CONTEXT_KEY,
                        java.util.List.of("weighted"))));

        assertEquals(
                ExecutorSelectionPolicy.CONTEXT_KEY + " must be an object or strategy string",
                error.getSafeMessage());
    }

    @Test
    void policyIsImmutableSnapshot() {
        Map<String, Object> selection = new HashMap<>();
        selection.put("pool", "agentic-local");
        Map<String, Object> source = new HashMap<>();
        source.put(ExecutorSelectionPolicy.CONTEXT_KEY, selection);

        ExecutorSelectionPolicy policy = ExecutorSelectionPolicy.fromContext(source);
        selection.put("pool", "mutated");

        assertEquals("agentic-local", policy.context().get("pool"));
        assertThrows(UnsupportedOperationException.class, () -> policy.context().put("pool", "mutated"));
        assertThrows(UnsupportedOperationException.class,
                () -> policy.toSelectionContext().put("pool", "mutated"));
    }

    @Test
    void emptyPolicyHasNoStrategy() {
        ExecutorSelectionPolicy policy = ExecutorSelectionPolicy.none();

        assertTrue(policy.context().isEmpty());
        assertTrue(policy.requiredCapabilities().isEmpty());
        assertTrue(policy.preferredCapabilities().isEmpty());
        assertTrue(policy.excludedCapabilities().isEmpty());
        assertTrue(policy.resourceRequirements().isEmpty());
        assertTrue(policy.toSelectionContext().isEmpty());
    }
}
