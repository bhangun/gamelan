package tech.kayys.gamelan.engine.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tech.kayys.gamelan.engine.error.GamelanException;
import tech.kayys.gamelan.engine.executor.ExecutorSelectionPolicy;
import tech.kayys.gamelan.engine.node.InputDefinition;
import tech.kayys.gamelan.engine.node.NodeDefinition;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.node.NodeType;
import tech.kayys.gamelan.engine.node.OutputDefinition;
import tech.kayys.gamelan.engine.run.RetryPolicy;
import tech.kayys.gamelan.engine.run.Transition;
import tech.kayys.gamelan.engine.run.Transition.TransitionType;
import tech.kayys.gamelan.engine.run.ValidationResult;
import tech.kayys.gamelan.engine.saga.CompensationPolicy;
import tech.kayys.gamelan.engine.tenant.TenantId;

class WorkflowDefinitionValidationTest {

    private static final TenantId TENANT = TenantId.of("tenant-1");

    @Test
    void validate_returnsDetailedErrorsForInvalidGraph() {
        WorkflowDefinition definition = workflow(
                WorkflowMode.DAG,
                List.of(
                        node("start"),
                        node("start"),
                        node("worker", List.of("worker", "missing"), List.of(transition("unknown")))),
                Map.of("", new InputDefinition("bad", "string", false, null, null)),
                Map.of("", new OutputDefinition("bad", "string", null)));

        ValidationResult result = definition.validate();

        assertFalse(result.isValid());
        assertEquals("Invalid workflow definition", result.message());
        assertTrue(result.errors().contains("Duplicate node id: start"));
        assertTrue(result.errors().contains("Node worker cannot depend on itself"));
        assertTrue(result.errors().contains("Node worker references unknown dependency: missing"));
        assertTrue(result.errors().contains("Node worker transitions to unknown node: unknown"));
        assertTrue(result.errors().contains("Workflow input name cannot be blank"));
        assertTrue(result.errors().contains("Workflow output name cannot be blank"));
    }

    @Test
    void validate_rejectsCyclesOnlyForDagMode() {
        WorkflowDefinition dag = workflow(
                WorkflowMode.DAG,
                List.of(node("anchor"), node("a", List.of("b"), List.of()), node("b", List.of("a"), List.of())),
                Map.of(),
                Map.of());
        WorkflowDefinition flow = workflow(
                WorkflowMode.FLOW,
                List.of(node("anchor"), node("a", List.of("b"), List.of()), node("b", List.of("a"), List.of())),
                Map.of(),
                Map.of());

        assertFalse(dag.validate().isValid());
        assertTrue(dag.validate().errors().contains("DAG workflow contains circular dependencies"));
        assertTrue(flow.validate().isValid());
    }

    @Test
    void validate_rejectsInvalidExecutorSelectionPolicyValues() {
        WorkflowDefinition definition = workflow(
                WorkflowMode.FLOW,
                List.of(node("start", Map.of(
                        ExecutorSelectionPolicy.CONTEXT_KEY,
                        Map.of(ExecutorSelectionPolicy.CONTEXT_MIN_MEMORY_MB_KEY, -1)))),
                Map.of(),
                Map.of());

        ValidationResult result = definition.validate();

        assertFalse(result.isValid());
        assertTrue(result.errors().stream().anyMatch(error -> error.contains(
                "Node start has invalid executor selection: Invalid executor resource requirement minMemoryMb: -1")));
    }

    @Test
    void validate_rejectsImpossibleExecutorSelectionCapabilityCombinations() {
        WorkflowDefinition definition = workflow(
                WorkflowMode.FLOW,
                List.of(node("start", Map.of(
                        ExecutorSelectionPolicy.CONTEXT_KEY,
                        Map.of(
                                ExecutorSelectionPolicy.CONTEXT_REQUIRED_CAPABILITIES_KEY, List.of("coding"),
                                ExecutorSelectionPolicy.CONTEXT_EXCLUDED_CAPABILITIES_KEY, List.of("coding"))))),
                Map.of(),
                Map.of());

        ValidationResult result = definition.validate();

        assertFalse(result.isValid());
        assertTrue(result.errors().contains(
                "Node start has invalid executor selection: required and excluded capabilities overlap: [coding]"));
    }

    @Test
    void validate_rejectsUnsupportedExecutorSelectionShape() {
        WorkflowDefinition definition = workflow(
                WorkflowMode.FLOW,
                List.of(node("start", Map.of(ExecutorSelectionPolicy.CONTEXT_KEY, List.of("weighted")))),
                Map.of(),
                Map.of());

        ValidationResult result = definition.validate();

        assertFalse(result.isValid());
        assertTrue(result.errors().contains(
                "Node start has invalid executor selection: __executor_selection__ must be an object or strategy string"));
    }

    @Test
    void buildAndValidate_throwsWithActionableMessage() {
        GamelanException exception = assertThrows(GamelanException.class, () -> WorkflowDefinition.builder()
                .id(WorkflowDefinitionId.of("wf"))
                .tenantId(TENANT)
                .name("wf")
                .version("1")
                .nodes(List.of())
                .buildAndValidate());

        assertTrue(exception.getSafeMessage().contains("Workflow must have at least one node"));
    }

    @Test
    void constructorDefaultsMetadataToSystemMetadata() {
        WorkflowDefinition definition = workflow(WorkflowMode.FLOW, List.of(node("start")), Map.of(), Map.of());

        assertNotNull(definition.metadata());
        assertNotNull(definition.metadata().createdAt());
        assertEquals("system", definition.metadata().createdBy());
        assertTrue(definition.metadata().labels().isEmpty());
        assertTrue(definition.metadata().annotations().isEmpty());
    }

    @Test
    void workflowDefinitionIdRejectsBlankValue() {
        GamelanException exception = assertThrows(GamelanException.class, () -> WorkflowDefinitionId.of(" "));

        assertEquals("WorkflowDefinitionId cannot be blank", exception.getSafeMessage());
    }

    private static WorkflowDefinition workflow(
            WorkflowMode mode,
            List<NodeDefinition> nodes,
            Map<String, InputDefinition> inputs,
            Map<String, OutputDefinition> outputs) {
        return new WorkflowDefinition(
                WorkflowDefinitionId.of("wf"),
                TENANT,
                "wf",
                "1",
                "test",
                mode,
                nodes,
                inputs,
                outputs,
                null,
                RetryPolicy.none(),
                CompensationPolicy.disabled());
    }

    private static NodeDefinition node(String id) {
        return node(id, List.of(), List.of());
    }

    private static NodeDefinition node(String id, Map<String, Object> configuration) {
        return new NodeDefinition(
                NodeId.of(id),
                id,
                NodeType.TASK,
                "local",
                configuration,
                List.of(),
                List.of(),
                RetryPolicy.none(),
                Duration.ZERO,
                false);
    }

    private static NodeDefinition node(String id, List<String> dependsOn, List<Transition> transitions) {
        return new NodeDefinition(
                NodeId.of(id),
                id,
                NodeType.TASK,
                "local",
                Map.of(),
                dependsOn.stream().map(NodeId::of).toList(),
                transitions,
                RetryPolicy.none(),
                Duration.ZERO,
                false);
    }

    private static Transition transition(String targetNodeId) {
        return new Transition(NodeId.of(targetNodeId), null, TransitionType.SUCCESS);
    }
}
