package tech.kayys.gamelan.workflow;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tech.kayys.gamelan.core.workflow.WorkflowDefinitionRegistry;

import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.engine.error.ErrorCode;
import tech.kayys.gamelan.engine.error.GamelanException;
import tech.kayys.gamelan.engine.io.dto.InputDefinitionDto;
import tech.kayys.gamelan.engine.io.dto.OutputDefinitionDto;
import tech.kayys.gamelan.engine.node.NodeType;
import tech.kayys.gamelan.engine.node.dto.NodeDefinitionDto;
import tech.kayys.gamelan.engine.run.dto.RetryPolicyDto;
import tech.kayys.gamelan.engine.saga.dto.CompensationPolicyDto;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.transition.dto.TransitionDto;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinition;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinitionId;
import tech.kayys.gamelan.engine.workflow.dto.CreateWorkflowDefinitionRequest;
import tech.kayys.gamelan.engine.workflow.dto.UpdateWorkflowDefinitionRequest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WorkflowDefinitionServiceTest {

    WorkflowDefinitionService service;

    RecordingDefinitionRegistry registry;
    RecordingDefinitionCompiler definitionCompiler;

    @BeforeEach
    void setUp() {
        service = new WorkflowDefinitionService();
        registry = new RecordingDefinitionRegistry();
        definitionCompiler = new RecordingDefinitionCompiler();
        service.registry = registry;
        service.definitionCompiler = definitionCompiler;
        service.admissionService = admissionService(definitionCompiler);
    }

    @Test
    void create_whenCalled_registersMappedWorkflowDefinition() {
        CreateWorkflowDefinitionRequest request = CreateWorkflowDefinitionRequest.builder()
                .name("test-workflow")
                .version("1.0.0")
                .nodes(List.of(nodeDto("start", "TASK")))
                .inputs(Map.of())
                .outputs(Map.of())
                .metadata(Map.of())
                .build();

        TenantId tenantId = new TenantId("tenant1");

        var result = service.create(request, tenantId)
                .await().indefinitely();

        assertNotNull(result);
        assertEquals("test-workflow", result.name());
        assertEquals("1.0.0", result.version());
        assertEquals(tenantId, result.tenantId());
        assertEquals(result, registry.registered);
        assertEquals(tenantId, registry.registeredTenant);
        assertEquals(result, definitionCompiler.compiled);
        assertEquals(1, definitionCompiler.compileCount);
    }

    @Test
    void create_whenWorkflowNameIsBlank_failsWithWorkflowInvalidDefinition() {
        CreateWorkflowDefinitionRequest request = CreateWorkflowDefinitionRequest.builder()
                .name(" ")
                .version("1.0.0")
                .nodes(List.of(nodeDto("start", "TASK")))
                .build();

        GamelanException exception = assertCreateFails(request);

        assertTrue(exception.getSafeMessage().contains("workflow name is required"));
    }

    @Test
    void create_whenNodeTypeUsesLowerCase_normalizesEnumAndDefaultsExecutorType() {
        CreateWorkflowDefinitionRequest request = CreateWorkflowDefinitionRequest.builder()
                .name("agent-workflow")
                .version("1.0.0")
                .nodes(List.of(nodeDto("start", "task")))
                .build();

        WorkflowDefinition result = service.create(request, TenantId.of("tenant1"))
                .await().indefinitely();

        assertEquals(NodeType.TASK, result.nodes().get(0).type());
        assertEquals("unspecified", result.nodes().get(0).executorType());
    }

    @Test
    void create_whenWorkflowTopologyIsInvalid_failsBeforeRegisteringOrCompiling() {
        NodeDefinitionDto blocked = new NodeDefinitionDto(
                "blocked",
                "blocked",
                "TASK",
                null,
                Map.of(),
                List.of("missing"),
                List.of(),
                null,
                null,
                false);
        CreateWorkflowDefinitionRequest request = CreateWorkflowDefinitionRequest.builder()
                .name("bad-topology")
                .version("1.0.0")
                .nodes(List.of(blocked))
                .build();

        GamelanException exception = assertCreateFails(request);

        assertTrue(exception.getSafeMessage().contains("admission rejected"));
        assertTrue(exception.getSafeMessage().contains("references unknown dependency"));
        assertEquals(0, definitionCompiler.compileCount);
    }

    @Test
    void create_whenNodeTypeIsInvalid_failsWithWorkflowInvalidDefinition() {
        CreateWorkflowDefinitionRequest request = CreateWorkflowDefinitionRequest.builder()
                .name("bad-workflow")
                .version("1.0.0")
                .nodes(List.of(nodeDto("start", "not-a-node-type")))
                .build();

        GamelanException exception = assertCreateFails(request);

        assertTrue(exception.getSafeMessage().contains("Invalid node type for node start"));
        assertTrue(exception.getSafeMessage().contains("TASK"));
    }

    @Test
    void create_whenTransitionTypeIsInvalid_failsWithWorkflowInvalidDefinition() {
        NodeDefinitionDto start = new NodeDefinitionDto(
                "start",
                "start",
                "TASK",
                null,
                Map.of(),
                List.of(),
                List.of(new TransitionDto("end", null, "sometimes")),
                null,
                null,
                false);
        CreateWorkflowDefinitionRequest request = CreateWorkflowDefinitionRequest.builder()
                .name("bad-workflow")
                .version("1.0.0")
                .nodes(List.of(start, nodeDto("end", "TASK")))
                .build();

        GamelanException exception = assertCreateFails(request);

        assertTrue(exception.getSafeMessage().contains("Invalid transition type for node start"));
        assertTrue(exception.getSafeMessage().contains("SUCCESS"));
    }

    @Test
    void create_whenCompensationStrategyIsInvalid_failsWithWorkflowInvalidDefinition() {
        CreateWorkflowDefinitionRequest request = CreateWorkflowDefinitionRequest.builder()
                .name("bad-workflow")
                .version("1.0.0")
                .nodes(List.of(nodeDto("start", "TASK")))
                .compensationPolicy(new CompensationPolicyDto("eventually", 30, true))
                .build();

        GamelanException exception = assertCreateFails(request);

        assertTrue(exception.getSafeMessage().contains("Invalid compensation strategy"));
        assertTrue(exception.getSafeMessage().contains("SEQUENTIAL"));
    }

    @Test
    void create_whenRetryPolicyIsInvalid_failsWithWorkflowInvalidDefinition() {
        CreateWorkflowDefinitionRequest request = CreateWorkflowDefinitionRequest.builder()
                .name("bad-workflow")
                .version("1.0.0")
                .nodes(List.of(nodeDto("start", "TASK")))
                .retryPolicy(new RetryPolicyDto(0, 1, 10, 2.0, List.of()))
                .build();

        GamelanException exception = assertCreateFails(request);

        assertTrue(exception.getSafeMessage().contains("Invalid retry policy"));
        assertTrue(exception.getSafeMessage().contains("maxAttempts"));
    }

    @Test
    void create_whenInputNameIsBlank_failsWithWorkflowInvalidDefinition() {
        CreateWorkflowDefinitionRequest request = CreateWorkflowDefinitionRequest.builder()
                .name("bad-workflow")
                .version("1.0.0")
                .nodes(List.of(nodeDto("start", "TASK")))
                .inputs(Map.of("", new InputDefinitionDto("prompt", "string", true, null, null)))
                .build();

        GamelanException exception = assertCreateFails(request);

        assertTrue(exception.getSafeMessage().contains("workflow input name is required"));
    }

    @Test
    void create_whenInputDtoNameIsMissing_usesMapKeyAsCanonicalName() {
        CreateWorkflowDefinitionRequest request = CreateWorkflowDefinitionRequest.builder()
                .name("workflow")
                .version("1.0.0")
                .nodes(List.of(nodeDto("start", "TASK")))
                .inputs(Map.of("prompt", new InputDefinitionDto(null, "string", true, null, null)))
                .build();

        WorkflowDefinition result = service.create(request, TenantId.of("tenant1"))
                .await().indefinitely();

        assertEquals("prompt", result.inputs().get("prompt").name());
    }

    @Test
    void create_whenInputDtoNameMismatchesMapKey_failsWithWorkflowInvalidDefinition() {
        CreateWorkflowDefinitionRequest request = CreateWorkflowDefinitionRequest.builder()
                .name("bad-workflow")
                .version("1.0.0")
                .nodes(List.of(nodeDto("start", "TASK")))
                .inputs(Map.of("prompt", new InputDefinitionDto("question", "string", true, null, null)))
                .build();

        GamelanException exception = assertCreateFails(request);

        assertTrue(exception.getSafeMessage().contains("workflow input name 'question' must match map key 'prompt'"));
    }

    @Test
    void create_whenOutputTypeIsBlank_failsWithWorkflowInvalidDefinition() {
        CreateWorkflowDefinitionRequest request = CreateWorkflowDefinitionRequest.builder()
                .name("bad-workflow")
                .version("1.0.0")
                .nodes(List.of(nodeDto("start", "TASK")))
                .outputs(Map.of("answer", new OutputDefinitionDto("answer", " ", null)))
                .build();

        GamelanException exception = assertCreateFails(request);

        assertTrue(exception.getSafeMessage().contains("workflow output type for answer is required"));
    }

    @Test
    void list_whenCalled_delegatesToRegistry() {
        TenantId tenantId = new TenantId("tenant1");
        registry.definitions = List.of(WorkflowDefinition.builder()
                .id(WorkflowDefinitionId.of("wf1"))
                .tenantId(tenantId)
                .name("wf")
                .version("1")
                .build());

        var result = service.list(tenantId, true)
                .await().indefinitely();

        assertEquals(registry.definitions, result);
        assertEquals(tenantId, registry.listTenant);
        assertTrue(registry.listActiveOnly);
    }

    @Test
    void update_whenCalled_persistsDescriptionAndMergedMetadata() {
        WorkflowDefinitionId id = new WorkflowDefinitionId("wf1");
        TenantId tenantId = new TenantId("tenant1");
        registry.definition = WorkflowDefinition.builder()
                .id(id)
                .tenantId(tenantId)
                .name("wf")
                .version("1")
                .metadata(new tech.kayys.gamelan.engine.workflow.WorkflowMetadata(
                        Map.of("owner", "platform"),
                        Map.of("origin", "test"),
                        java.time.Instant.now(),
                        "system"))
                .nodes(List.of(node("start")))
                .build();

        UpdateWorkflowDefinitionRequest request = new UpdateWorkflowDefinitionRequest(
                "updated",
                null,
                Map.of("domain", "agentic-ai"));

        var result = service.update(id, request, tenantId)
                .await().indefinitely();

        assertEquals("updated", result.description());
        assertEquals("platform", result.metadata().labels().get("owner"));
        assertEquals("agentic-ai", result.metadata().labels().get("domain"));
        assertEquals(result, registry.updated);
        assertTrue(registry.updatedActive);
        assertEquals(id, definitionCompiler.invalidatedId);
        assertEquals(tenantId, definitionCompiler.invalidatedTenant);
        assertEquals(result, definitionCompiler.compiled);
    }

    @Test
    void update_whenInactiveRequested_deactivatesDefinition() {
        WorkflowDefinitionId id = new WorkflowDefinitionId("wf1");
        TenantId tenantId = new TenantId("tenant1");
        registry.definition = definition(id, tenantId);

        var result = service.update(id, new UpdateWorkflowDefinitionRequest(null, false, null), tenantId)
                .await().indefinitely();

        assertEquals(id, result.id());
        assertFalse(registry.updatedActive);
        assertEquals(id, definitionCompiler.invalidatedId);
        assertEquals(tenantId, definitionCompiler.invalidatedTenant);
        assertEquals(0, definitionCompiler.compileCount);
    }

    @Test
    void update_whenReactivationRequested_loadsIncludingInactive() {
        WorkflowDefinitionId id = new WorkflowDefinitionId("wf1");
        TenantId tenantId = new TenantId("tenant1");
        registry.definition = definition(id, tenantId);

        service.update(id, new UpdateWorkflowDefinitionRequest(null, true, null), tenantId)
                .await().indefinitely();

        assertTrue(registry.loadedIncludingInactive);
        assertTrue(registry.updatedActive);
    }

    @Test
    void delete_whenCalled_deactivatesDefinition() {
        WorkflowDefinitionId id = new WorkflowDefinitionId("wf1");
        TenantId tenantId = new TenantId("tenant1");

        assertDoesNotThrow(() -> service.delete(id, tenantId).await().indefinitely());
        assertEquals(id, registry.deletedId);
        assertEquals(tenantId, registry.deletedTenant);
        assertEquals(id, definitionCompiler.invalidatedId);
        assertEquals(tenantId, definitionCompiler.invalidatedTenant);
    }

    private GamelanException assertCreateFails(CreateWorkflowDefinitionRequest request) {
        GamelanException exception = assertThrows(GamelanException.class,
                () -> service.create(request, TenantId.of("tenant1")).await().indefinitely());

        assertEquals(ErrorCode.WORKFLOW_INVALID_DEFINITION, exception.getErrorCode());
        assertNull(registry.registered);
        return exception;
    }

    private static WorkflowDefinitionAdmissionService admissionService(WorkflowDefinitionCompiler compiler) {
        WorkflowDefinitionAdmissionService admission = new WorkflowDefinitionAdmissionService();
        WorkflowValidator validator = new WorkflowValidator();
        validator.dagPluginEnabled = true;
        admission.validator = validator;
        admission.definitionCompiler = compiler;
        return admission;
    }

    private static NodeDefinitionDto nodeDto(String id, String type) {
        return new NodeDefinitionDto(
                id,
                id,
                type,
                null,
                Map.of(),
                List.of(),
                List.of(),
                null,
                null,
                false);
    }

    private static tech.kayys.gamelan.engine.node.NodeDefinition node(String id) {
        return new tech.kayys.gamelan.engine.node.NodeDefinition(
                tech.kayys.gamelan.engine.node.NodeId.of(id),
                id,
                tech.kayys.gamelan.engine.node.NodeType.TASK,
                "local",
                Map.of(),
                List.of(),
                List.of(),
                tech.kayys.gamelan.engine.run.RetryPolicy.none(),
                java.time.Duration.ZERO,
                false);
    }

    private static WorkflowDefinition definition(WorkflowDefinitionId id, TenantId tenantId) {
        return WorkflowDefinition.builder()
                .id(id)
                .tenantId(tenantId)
                .name("wf")
                .version("1")
                .nodes(List.of(node("start")))
                .build();
    }

    private static final class RecordingDefinitionRegistry extends WorkflowDefinitionRegistry {
        WorkflowDefinition registered;
        TenantId registeredTenant;
        List<WorkflowDefinition> definitions = List.of();
        TenantId listTenant;
        boolean listActiveOnly;
        WorkflowDefinition definition;
        WorkflowDefinition updated;
        boolean updatedActive;
        boolean loadedIncludingInactive;
        WorkflowDefinitionId deletedId;
        TenantId deletedTenant;

        @Override
        public Uni<WorkflowDefinition> register(WorkflowDefinition definition, TenantId tenantId) {
            registered = definition;
            registeredTenant = tenantId;
            return Uni.createFrom().item(definition);
        }

        @Override
        public Uni<List<WorkflowDefinition>> listDefinitions(TenantId tenantId, boolean activeOnly) {
            listTenant = tenantId;
            listActiveOnly = activeOnly;
            return Uni.createFrom().item(definitions);
        }

        @Override
        public Uni<WorkflowDefinition> getDefinition(WorkflowDefinitionId id, TenantId tenantId) {
            return Uni.createFrom().item(definition);
        }

        @Override
        public Uni<WorkflowDefinition> getDefinitionIncludingInactive(WorkflowDefinitionId id, TenantId tenantId) {
            loadedIncludingInactive = true;
            return Uni.createFrom().item(definition);
        }

        @Override
        public Uni<WorkflowDefinition> update(WorkflowDefinition definition, TenantId tenantId, boolean active) {
            updated = definition;
            updatedActive = active;
            return Uni.createFrom().item(definition);
        }

        @Override
        public Uni<Void> deleteDefinition(WorkflowDefinitionId id, TenantId tenantId) {
            deletedId = id;
            deletedTenant = tenantId;
            return Uni.createFrom().voidItem();
        }
    }

    private static final class RecordingDefinitionCompiler extends WorkflowDefinitionCompiler {
        WorkflowDefinitionId invalidatedId;
        TenantId invalidatedTenant;
        WorkflowDefinition compiled;
        int compileCount;

        @Override
        public CompiledWorkflowDefinition compile(WorkflowDefinition definition) {
            compiled = definition;
            compileCount++;
            return CompiledWorkflowDefinition.compile(definition);
        }

        @Override
        public int invalidate(WorkflowDefinitionId definitionId, TenantId tenantId) {
            invalidatedId = definitionId;
            invalidatedTenant = tenantId;
            return 1;
        }
    }
}
