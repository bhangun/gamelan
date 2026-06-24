package tech.kayys.gamelan.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tech.kayys.gamelan.engine.node.NodeDefinition;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.node.NodeType;
import tech.kayys.gamelan.engine.plugin.PluginContext;
import tech.kayys.gamelan.engine.plugin.PluginMetadata;
import tech.kayys.gamelan.engine.plugin.PluginService;
import tech.kayys.gamelan.engine.run.RetryPolicy;
import tech.kayys.gamelan.engine.run.ValidationResult;
import tech.kayys.gamelan.engine.saga.CompensationPolicy;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinition;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinitionId;
import tech.kayys.gamelan.engine.workflow.WorkflowMode;
import tech.kayys.gamelan.plugin.validator.WorkflowValidatorPlugin;

class WorkflowValidatorTest {

    private static final TenantId TENANT = TenantId.of("tenant-validator");

    private WorkflowValidator validator;

    @BeforeEach
    void setUp() {
        validator = new WorkflowValidator();
        validator.dagPluginEnabled = true;
    }

    @Test
    void validate_whenWorkflowIsNull_returnsControlledValidationFailure() {
        ValidationResult result = validator.validate(null).await().indefinitely();

        assertFalse(result.isValid());
        assertTrue(result.errors().contains("Workflow definition cannot be null"));
    }

    @Test
    void validate_preservesStructuralErrorsAsStructuredErrors() {
        WorkflowDefinition definition = workflow("wf-empty", WorkflowMode.FLOW, List.of());

        ValidationResult result = validator.validate(definition).await().indefinitely();

        assertFalse(result.isValid());
        assertTrue(result.errors().contains("Workflow must have at least one node"));
    }

    @Test
    void validate_includesOnlyPluginErrorSeverityMessages() {
        WorkflowValidatorPlugin plugin = new TestValidatorPlugin(
                "dag-validator",
                List.of(
                        new WorkflowValidatorPlugin.ValidationError(
                                "rule-1",
                                "branch must join",
                                "node:start",
                                WorkflowValidatorPlugin.ValidationError.Severity.ERROR),
                        new WorkflowValidatorPlugin.ValidationError(
                                "rule-2",
                                "optional warning",
                                "node:start",
                                WorkflowValidatorPlugin.ValidationError.Severity.WARNING)));
        validator.pluginService = pluginService(plugin);

        ValidationResult result = validator.validate(workflow("wf-dag", WorkflowMode.DAG, List.of(node("start"))))
                .await().indefinitely();

        assertFalse(result.isValid());
        assertTrue(result.errors().contains("dag-validator [node:start]: branch must join"));
        assertFalse(result.errors().toString().contains("optional warning"));
    }

    @Test
    void validate_convertsPluginFailureToValidationError() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        validator.meterRegistry = meterRegistry;
        WorkflowValidatorPlugin plugin = new FailingValidatorPlugin("fragile-validator");
        validator.pluginService = pluginService(plugin);

        ValidationResult result = validator.validate(workflow("wf-dag", WorkflowMode.DAG, List.of(node("start"))))
                .await().indefinitely();

        assertFalse(result.isValid());
        assertTrue(result.errors().get(0).contains("Workflow validator plugin fragile-validator failed"));
        assertTrue(result.errors().get(0).contains("boom"));
        assertEquals(1.0, counter(meterRegistry, "gamelan.workflow.validation.requests", "outcome", "invalid"));
        assertEquals(1.0, counter(meterRegistry, "gamelan.workflow.validation.errors", "source", "plugin_runtime"));
        assertEquals(1, timerCount(meterRegistry, "gamelan.workflow.validation.duration", "outcome", "invalid"));
    }

    @Test
    void validate_recordsValidationMetricsByOutcomeAndErrorSource() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        validator.meterRegistry = meterRegistry;

        validator.validate(workflow("wf-valid", WorkflowMode.FLOW, List.of(node("start"))))
                .await().indefinitely();
        validator.validate(workflow("wf-structural", WorkflowMode.FLOW, List.of()))
                .await().indefinitely();
        validator.pluginService = pluginService(new TestValidatorPlugin(
                "metrics-validator",
                List.of(new WorkflowValidatorPlugin.ValidationError(
                        "rule-1",
                        "requires explicit join",
                        "node:start",
                        WorkflowValidatorPlugin.ValidationError.Severity.ERROR))));
        validator.validate(workflow("wf-plugin", WorkflowMode.DAG, List.of(node("start"))))
                .await().indefinitely();
        validator.validate(null).await().indefinitely();

        assertEquals(1.0, counter(meterRegistry, "gamelan.workflow.validation.requests", "outcome", "valid"));
        assertEquals(2.0, counter(meterRegistry, "gamelan.workflow.validation.requests", "outcome", "invalid"));
        assertEquals(1.0, counter(meterRegistry, "gamelan.workflow.validation.requests", "outcome", "null_definition"));
        assertEquals(1.0, counter(meterRegistry, "gamelan.workflow.validation.errors", "source", "structural"));
        assertEquals(1.0, counter(meterRegistry, "gamelan.workflow.validation.errors", "source", "plugin_rule"));
        assertEquals(1.0, counter(meterRegistry, "gamelan.workflow.validation.errors", "source", "null_definition"));
        assertEquals(1, timerCount(meterRegistry, "gamelan.workflow.validation.duration", "outcome", "valid"));
        assertEquals(2, timerCount(meterRegistry, "gamelan.workflow.validation.duration", "outcome", "invalid"));
        assertEquals(1, timerCount(meterRegistry, "gamelan.workflow.validation.duration", "outcome", "null_definition"));
    }

    @Test
    void validate_convertsPluginServiceFailureToValidationError() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        validator.meterRegistry = meterRegistry;
        @SuppressWarnings("unchecked")
        Instance<PluginService> instance = mock(Instance.class);
        when(instance.isResolvable()).thenReturn(true);
        when(instance.get()).thenThrow(new IllegalStateException("container unavailable"));
        validator.pluginService = instance;

        ValidationResult result = validator.validate(workflow("wf-service-failure", WorkflowMode.DAG, List.of(node("start"))))
                .await().indefinitely();

        assertFalse(result.isValid());
        assertTrue(result.errors().get(0).contains("Workflow validator plugin service failed"));
        assertTrue(result.errors().get(0).contains("container unavailable"));
        assertEquals(1.0, counter(meterRegistry, "gamelan.workflow.validation.requests", "outcome", "invalid"));
        assertEquals(1.0, counter(meterRegistry, "gamelan.workflow.validation.errors", "source", "plugin_service"));
    }

    @Test
    void validate_convertsPluginLookupFailureToValidationError() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        validator.meterRegistry = meterRegistry;
        @SuppressWarnings("unchecked")
        Instance<PluginService> instance = mock(Instance.class);
        PluginService service = mock(PluginService.class);
        when(instance.isResolvable()).thenReturn(true);
        when(instance.get()).thenReturn(service);
        when(service.getPluginsByType(WorkflowValidatorPlugin.class))
                .thenThrow(new IllegalStateException("registry unavailable"));
        validator.pluginService = instance;

        ValidationResult result = validator.validate(workflow("wf-lookup-failure", WorkflowMode.DAG, List.of(node("start"))))
                .await().indefinitely();

        assertFalse(result.isValid());
        assertTrue(result.errors().get(0).contains("Workflow validator plugin lookup failed"));
        assertTrue(result.errors().get(0).contains("registry unavailable"));
        assertEquals(1.0, counter(meterRegistry, "gamelan.workflow.validation.requests", "outcome", "invalid"));
        assertEquals(1.0, counter(meterRegistry, "gamelan.workflow.validation.errors", "source", "plugin_lookup"));
    }

    private static Instance<PluginService> pluginService(WorkflowValidatorPlugin plugin) {
        @SuppressWarnings("unchecked")
        Instance<PluginService> instance = mock(Instance.class);
        PluginService service = mock(PluginService.class);
        when(instance.isResolvable()).thenReturn(true);
        when(instance.get()).thenReturn(service);
        when(service.getPluginsByType(WorkflowValidatorPlugin.class)).thenReturn(List.of(plugin));
        return instance;
    }

    private static WorkflowDefinition workflow(
            String id,
            WorkflowMode mode,
            List<NodeDefinition> nodes) {
        return new WorkflowDefinition(
                WorkflowDefinitionId.of(id),
                TENANT,
                id,
                "1.0.0",
                null,
                mode,
                nodes,
                Map.of(),
                Map.of(),
                null,
                RetryPolicy.none(),
                CompensationPolicy.disabled());
    }

    private static NodeDefinition node(String id) {
        return new NodeDefinition(
                NodeId.of(id),
                id,
                NodeType.TASK,
                "local",
                Map.of(),
                List.of(),
                List.of(),
                RetryPolicy.none(),
                Duration.ZERO,
                false);
    }

    private static double counter(SimpleMeterRegistry meterRegistry, String name, String... tags) {
        var counter = meterRegistry.find(name).tags(tags).counter();
        return counter != null ? counter.count() : 0.0;
    }

    private static long timerCount(SimpleMeterRegistry meterRegistry, String name, String... tags) {
        var timer = meterRegistry.find(name).tags(tags).timer();
        return timer != null ? timer.count() : 0;
    }

    private static class TestValidatorPlugin implements WorkflowValidatorPlugin {
        private final PluginMetadata metadata;
        private final List<ValidationError> errors;

        private TestValidatorPlugin(String id, List<ValidationError> errors) {
            this.metadata = new PluginMetadata(id, id, "1.0.0", "test", null, List.of(), Map.of());
            this.errors = errors;
        }

        @Override
        public List<ValidationError> validate(WorkflowDefinitionInfo definition) {
            return errors;
        }

        @Override
        public List<String> getValidationRules() {
            return List.of();
        }

        @Override
        public void initialize(PluginContext context) {
        }

        @Override
        public void start() {
        }

        @Override
        public void stop() {
        }

        @Override
        public PluginMetadata getMetadata() {
            return metadata;
        }
    }

    private static final class FailingValidatorPlugin extends TestValidatorPlugin {
        private FailingValidatorPlugin(String id) {
            super(id, List.of());
        }

        @Override
        public List<ValidationError> validate(WorkflowDefinitionInfo definition) {
            throw new IllegalStateException("boom");
        }
    }
}
