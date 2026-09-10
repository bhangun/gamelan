package tech.kayys.gamelan.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tech.kayys.gamelan.engine.error.GamelanException;
import tech.kayys.gamelan.engine.node.NodeDefinition;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.node.NodeType;
import tech.kayys.gamelan.engine.run.RetryPolicy;
import tech.kayys.gamelan.engine.run.ValidationResult;
import tech.kayys.gamelan.engine.saga.CompensationPolicy;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinition;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinitionId;
import tech.kayys.gamelan.engine.workflow.WorkflowMode;

class WorkflowDefinitionAdmissionServiceTest {

    private static final TenantId TENANT = TenantId.of("tenant-admission");

    private WorkflowDefinitionAdmissionService admission;
    private SimpleMeterRegistry meterRegistry;
    private RecordingCompiler compiler;

    @BeforeEach
    void setUp() {
        admission = new WorkflowDefinitionAdmissionService();
        admission.validator = new WorkflowValidator();
        compiler = new RecordingCompiler();
        admission.definitionCompiler = compiler;
        meterRegistry = new SimpleMeterRegistry();
        admission.meterRegistry = meterRegistry;
    }

    @Test
    void admit_whenDefinitionIsValid_recordsAcceptedOutcomeAndWarmsCompiler() {
        WorkflowDefinition definition = workflow("wf-valid", List.of(node("start")));

        WorkflowDefinition result = admission.admit(definition).await().indefinitely();

        assertSame(definition, result);
        assertSame(definition, compiler.compiled);
        assertEquals(1, compiler.compileCount);
        assertAdmissionOutcome("accepted", 1.0, 1);
        assertAdmissionOutcome("rejected", 0.0, 0);
    }

    @Test
    void admit_whenDefinitionIsInvalid_recordsRejectedOutcomeWithoutCompiling() {
        WorkflowDefinition definition = workflow(
                "wf-invalid",
                List.of(node("blocked", NodeId.of("missing"))));

        GamelanException error = assertThrows(GamelanException.class,
                () -> admission.admit(definition).await().indefinitely());

        assertTrue(error.getSafeMessage().contains("admission rejected"));
        assertTrue(error.getSafeMessage().contains("references unknown dependency"));
        assertEquals(0, compiler.compileCount);
        assertAdmissionOutcome("rejected", 1.0, 1);
        assertAdmissionOutcome("accepted", 0.0, 0);
    }

    @Test
    void admit_whenValidatorFails_recordsValidationFailureOutcome() {
        admission.validator = new WorkflowValidator() {
            @Override
            public Uni<ValidationResult> validate(WorkflowDefinition workflow) {
                return Uni.createFrom().failure(new IllegalStateException("validator unavailable"));
            }
        };
        WorkflowDefinition definition = workflow("wf-validator-failure", List.of(node("start")));

        GamelanException error = assertThrows(GamelanException.class,
                () -> admission.admit(definition).await().indefinitely());

        assertTrue(error.getSafeMessage().contains("validation failed"));
        assertTrue(error.getSafeMessage().contains("validator unavailable"));
        assertEquals(0, compiler.compileCount);
        assertAdmissionOutcome("validation_failure", 1.0, 1);
    }

    @Test
    void admit_whenCompilationFails_recordsCompilationFailureOutcome() {
        admission.definitionCompiler = new WorkflowDefinitionCompiler() {
            @Override
            public CompiledWorkflowDefinition compile(WorkflowDefinition definition) {
                throw new IllegalStateException("compile unavailable");
            }
        };
        WorkflowDefinition definition = workflow("wf-compile-failure", List.of(node("start")));

        GamelanException error = assertThrows(GamelanException.class,
                () -> admission.admit(definition).await().indefinitely());

        assertTrue(error.getSafeMessage().contains("compilation failed"));
        assertTrue(error.getSafeMessage().contains("compile unavailable"));
        assertAdmissionOutcome("compilation_failure", 1.0, 1);
        assertAdmissionOutcome("accepted", 0.0, 0);
    }

    private void assertAdmissionOutcome(String outcome, double expectedCounter, long expectedTimerCount) {
        assertEquals(expectedCounter, counter("gamelan.workflow.definition.admissions", "outcome", outcome));
        assertEquals(expectedTimerCount, timerCount(
                "gamelan.workflow.definition.admission.duration",
                "outcome",
                outcome));
    }

    private double counter(String name, String... tags) {
        var counter = meterRegistry.find(name).tags(tags).counter();
        return counter != null ? counter.count() : 0.0;
    }

    private long timerCount(String name, String... tags) {
        var timer = meterRegistry.find(name).tags(tags).timer();
        return timer != null ? timer.count() : 0;
    }

    private static WorkflowDefinition workflow(String id, List<NodeDefinition> nodes) {
        return new WorkflowDefinition(
                WorkflowDefinitionId.of(id),
                TENANT,
                id,
                "1.0.0",
                null,
                WorkflowMode.FLOW,
                nodes,
                Map.of(),
                Map.of(),
                null,
                RetryPolicy.none(),
                CompensationPolicy.disabled());
    }

    private static NodeDefinition node(String id, NodeId... dependencies) {
        return new NodeDefinition(
                NodeId.of(id),
                id,
                NodeType.TASK,
                "local",
                Map.of(),
                List.of(dependencies),
                List.of(),
                RetryPolicy.none(),
                Duration.ZERO,
                false);
    }

    private static final class RecordingCompiler extends WorkflowDefinitionCompiler {
        private WorkflowDefinition compiled;
        private int compileCount;

        @Override
        public CompiledWorkflowDefinition compile(WorkflowDefinition definition) {
            compiled = definition;
            compileCount++;
            return CompiledWorkflowDefinition.compile(definition);
        }
    }
}
