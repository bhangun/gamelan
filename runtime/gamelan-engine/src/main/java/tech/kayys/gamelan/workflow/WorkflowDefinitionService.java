package tech.kayys.gamelan.workflow;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import tech.kayys.gamelan.engine.workflow.dto.CreateWorkflowDefinitionRequest;
import tech.kayys.gamelan.engine.workflow.*;
import tech.kayys.gamelan.core.workflow.WorkflowDefinitionRegistry;
import tech.kayys.gamelan.engine.error.ErrorCode;
import tech.kayys.gamelan.engine.error.GamelanException;
import tech.kayys.gamelan.engine.node.NodeDefinition;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.node.NodeType;
import tech.kayys.gamelan.engine.node.dto.NodeDefinitionDto;
import tech.kayys.gamelan.engine.run.Transition;
import tech.kayys.gamelan.engine.saga.CompensationPolicy;
import tech.kayys.gamelan.engine.saga.CompensationStrategy;
import tech.kayys.gamelan.engine.saga.dto.CompensationPolicyDto;
import tech.kayys.gamelan.engine.transition.dto.TransitionDto;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Workflow definition service
 */
@ApplicationScoped
public class WorkflowDefinitionService implements tech.kayys.gamelan.engine.workflow.WorkflowDefinitionService {

    @Inject
    WorkflowDefinitionRegistry registry;

    @Inject
    WorkflowDefinitionCompiler definitionCompiler;

    @Inject
    WorkflowDefinitionAdmissionService admissionService;

    public Uni<WorkflowDefinition> create(
            CreateWorkflowDefinitionRequest request,
            tech.kayys.gamelan.engine.tenant.TenantId tenantId) {
        Objects.requireNonNull(request, "Create workflow definition request cannot be null");
        Objects.requireNonNull(tenantId, "Tenant ID cannot be null");

        try {
            String workflowName = requireText(request.name(), "workflow name");
            String workflowVersion = requireText(request.version(), "workflow version");
            WorkflowDefinition workflow = WorkflowDefinition.builder()
                    .id(WorkflowDefinitionId.of(UUID.randomUUID().toString()))
                    .tenantId(tenantId)
                    .name(workflowName)
                    .version(workflowVersion)
                    .description(request.description())
                    .nodes(mapNodeDefinitions(request.nodes()))
                    .inputs(mapInputDefinitions(request.inputs()))
                    .outputs(mapOutputDefinitions(request.outputs()))
                    .defaultRetryPolicy(mapRetryPolicy(request.retryPolicy()))
                    .compensationPolicy(mapCompensationPolicy(request.compensationPolicy()))
                    .metadata(WorkflowMetadata.system(request.metadata()))
                    .build();

            return admissionService.admit(workflow)
                    .flatMap(admitted -> registry.register(admitted, tenantId));
        } catch (GamelanException e) {
            return Uni.createFrom().failure(e);
        }
    }

    private List<NodeDefinition> mapNodeDefinitions(List<NodeDefinitionDto> dtos) {
        if (dtos == null)
            return List.of();
        return dtos.stream().map(this::mapNodeDefinition).toList();
    }

    private NodeDefinition mapNodeDefinition(NodeDefinitionDto dto) {
        if (dto == null) {
            throw invalidDefinition("Workflow node definition cannot be null");
        }

        String nodeIdValue = requireText(dto.id(), "node id");
        NodeId nodeId = nodeId(nodeIdValue, "node id");
        NodeType nodeType = enumValue(NodeType.class, dto.type(), "node type for node " + nodeIdValue);

        List<NodeId> dependsOn = dto.dependsOn() != null
                ? dto.dependsOn().stream()
                        .map(value -> nodeId(value, "dependency for node " + nodeIdValue))
                        .toList()
                : List.of();

        List<Transition> transitions = dto.transitions() != null
                ? dto.transitions().stream()
                        .map(transition -> mapTransition(nodeIdValue, transition))
                        .toList()
                : List.of();

        try {
            return new NodeDefinition(
                    nodeId,
                    dto.name(),
                    nodeType,
                    executorType(dto.executorType()),
                    dto.configuration(),
                    dependsOn,
                    transitions,
                    mapRetryPolicy(dto.retryPolicy()),
                    java.time.Duration.ofSeconds(dto.timeoutSeconds() != null ? dto.timeoutSeconds() : 30),
                    dto.critical());
        } catch (GamelanException e) {
            throw invalidDefinition("Invalid node " + nodeIdValue + ": " + e.getSafeMessage(), e);
        }
    }

    private Transition mapTransition(String sourceNodeId, TransitionDto dto) {
        if (dto == null) {
            throw invalidDefinition("Transition for node " + sourceNodeId + " cannot be null");
        }

        return new Transition(
                dto.targetNodeId() != null
                        ? nodeId(dto.targetNodeId(), "transition target for node " + sourceNodeId)
                        : null,
                dto.condition(),
                enumValue(Transition.TransitionType.class, dto.type(), "transition type for node " + sourceNodeId));
    }

    private Map<String, tech.kayys.gamelan.engine.node.InputDefinition> mapInputDefinitions(
            Map<String, tech.kayys.gamelan.engine.io.dto.InputDefinitionDto> dtos) {
        if (dtos == null) {
            return Map.of();
        }

        Map<String, tech.kayys.gamelan.engine.node.InputDefinition> inputs = new LinkedHashMap<>();
        dtos.forEach((key, value) -> {
            String inputName = requireText(key, "workflow input name");
            inputs.put(inputName, mapInputDefinition(inputName, value));
        });
        return inputs;
    }

    private tech.kayys.gamelan.engine.node.InputDefinition mapInputDefinition(
            String key,
            tech.kayys.gamelan.engine.io.dto.InputDefinitionDto dto) {
        if (dto == null) {
            throw invalidDefinition("Workflow input definition cannot be null");
        }

        String inputName = definitionName(key, dto.name(), "workflow input");
        return new tech.kayys.gamelan.engine.node.InputDefinition(
                inputName,
                requireText(dto.type(), "workflow input type for " + inputName),
                dto.required(),
                dto.defaultValue(),
                dto.description());
    }

    private Map<String, tech.kayys.gamelan.engine.node.OutputDefinition> mapOutputDefinitions(
            Map<String, tech.kayys.gamelan.engine.io.dto.OutputDefinitionDto> dtos) {
        if (dtos == null) {
            return Map.of();
        }

        Map<String, tech.kayys.gamelan.engine.node.OutputDefinition> outputs = new LinkedHashMap<>();
        dtos.forEach((key, value) -> {
            String outputName = requireText(key, "workflow output name");
            outputs.put(outputName, mapOutputDefinition(outputName, value));
        });
        return outputs;
    }

    private tech.kayys.gamelan.engine.node.OutputDefinition mapOutputDefinition(
            String key,
            tech.kayys.gamelan.engine.io.dto.OutputDefinitionDto dto) {
        if (dto == null) {
            throw invalidDefinition("Workflow output definition cannot be null");
        }

        String outputName = definitionName(key, dto.name(), "workflow output");
        return new tech.kayys.gamelan.engine.node.OutputDefinition(
                outputName,
                requireText(dto.type(), "workflow output type for " + outputName),
                dto.description());
    }

    private tech.kayys.gamelan.engine.run.RetryPolicy mapRetryPolicy(
            tech.kayys.gamelan.engine.run.dto.RetryPolicyDto dto) {
        if (dto == null)
            return null;

        try {
            return new tech.kayys.gamelan.engine.run.RetryPolicy(
                    dto.maxAttempts(),
                    java.time.Duration.ofSeconds(dto.initialDelaySeconds()),
                    java.time.Duration.ofSeconds(dto.maxDelaySeconds()),
                    dto.backoffMultiplier(),
                    dto.retryableExceptions());
        } catch (GamelanException e) {
            throw invalidDefinition(e.getSafeMessage(), e);
        }
    }

    private CompensationPolicy mapCompensationPolicy(CompensationPolicyDto dto) {
        if (dto == null)
            return null;

        CompensationStrategy strategy = dto.strategy() != null
                ? enumValue(CompensationStrategy.class, dto.strategy(), "compensation strategy")
                : CompensationStrategy.SEQUENTIAL;

        return new CompensationPolicy(
                true,
                strategy,
                Duration.ofSeconds(dto.timeoutSeconds()),
                dto.failOnCompensationError(),
                3 // Default max retries
        );
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw invalidDefinition(field + " is required");
        }
        return value;
    }

    private static String executorType(String value) {
        return value != null && !value.isBlank() ? value : "unspecified";
    }

    private static String definitionName(String key, String value, String field) {
        if (value == null || value.isBlank()) {
            return key;
        }
        if (!key.equals(value)) {
            throw invalidDefinition(field + " name '" + value + "' must match map key '" + key + "'");
        }
        return value;
    }

    private static NodeId nodeId(String value, String field) {
        try {
            return NodeId.of(requireText(value, field));
        } catch (GamelanException e) {
            throw invalidDefinition("Invalid " + field + ": " + e.getSafeMessage(), e);
        }
    }

    private static <E extends Enum<E>> E enumValue(Class<E> enumType, String value, String field) {
        String normalized = requireText(value, field).trim().toUpperCase(Locale.ROOT);
        try {
            return Enum.valueOf(enumType, normalized);
        } catch (IllegalArgumentException e) {
            throw invalidDefinition("Invalid " + field + ": " + value
                    + ". Supported values: " + supportedValues(enumType), e);
        }
    }

    private static <E extends Enum<E>> String supportedValues(Class<E> enumType) {
        return Arrays.stream(enumType.getEnumConstants())
                .map(Enum::name)
                .collect(java.util.stream.Collectors.joining(", "));
    }

    private static GamelanException invalidDefinition(String message) {
        return new GamelanException(ErrorCode.WORKFLOW_INVALID_DEFINITION, message);
    }

    private static GamelanException invalidDefinition(String message, Throwable cause) {
        return new GamelanException(ErrorCode.WORKFLOW_INVALID_DEFINITION, message, cause);
    }

    public Uni<WorkflowDefinition> get(
            tech.kayys.gamelan.engine.workflow.WorkflowDefinitionId id,
            tech.kayys.gamelan.engine.tenant.TenantId tenantId) {
        return registry.getDefinition(id, tenantId);
    }

    public Uni<List<WorkflowDefinition>> list(
            tech.kayys.gamelan.engine.tenant.TenantId tenantId,
            boolean activeOnly) {
        return registry.listDefinitions(tenantId, activeOnly);
    }

    @Override
    public Uni<WorkflowDefinition> getByName(String name, tech.kayys.gamelan.engine.tenant.TenantId tenantId) {
        return registry.getByName(name, tenantId);
    }

    public Uni<WorkflowDefinition> update(
            tech.kayys.gamelan.engine.workflow.WorkflowDefinitionId id,
            tech.kayys.gamelan.engine.workflow.dto.UpdateWorkflowDefinitionRequest request,
            tech.kayys.gamelan.engine.tenant.TenantId tenantId) {
        Objects.requireNonNull(id, "Workflow definition ID cannot be null");
        Objects.requireNonNull(request, "Update workflow definition request cannot be null");
        Objects.requireNonNull(tenantId, "Tenant ID cannot be null");

        Uni<WorkflowDefinition> load = Boolean.TRUE.equals(request.isActive())
                ? registry.getDefinitionIncludingInactive(id, tenantId)
                : registry.getDefinition(id, tenantId);

        return load.flatMap(existing -> {
            if (existing == null) {
                return Uni.createFrom().failure(new GamelanException(
                        ErrorCode.WORKFLOW_NOT_FOUND,
                        "Workflow definition not found: " + id.value()));
            }

            WorkflowDefinition updated = applyUpdate(existing, request);
            boolean active = request.isActive() == null || request.isActive();
            invalidateCompiledDefinition(updated.id(), tenantId);
            Uni<WorkflowDefinition> admitted = active
                    ? admissionService.admit(updated)
                    : Uni.createFrom().item(updated);
            return admitted.flatMap(definition -> registry.update(definition, tenantId, active));
        });
    }

    public Uni<Void> delete(
            tech.kayys.gamelan.engine.workflow.WorkflowDefinitionId id,
            tech.kayys.gamelan.engine.tenant.TenantId tenantId) {
        Objects.requireNonNull(id, "Workflow definition ID cannot be null");
        Objects.requireNonNull(tenantId, "Tenant ID cannot be null");
        return registry.deleteDefinition(id, tenantId)
                .invoke(() -> invalidateCompiledDefinition(id, tenantId));
    }

    private void invalidateCompiledDefinition(
            WorkflowDefinitionId id,
            tech.kayys.gamelan.engine.tenant.TenantId tenantId) {
        if (definitionCompiler != null) {
            definitionCompiler.invalidate(id, tenantId);
        }
    }

    private WorkflowDefinition applyUpdate(
            WorkflowDefinition existing,
            tech.kayys.gamelan.engine.workflow.dto.UpdateWorkflowDefinitionRequest request) {

        return new WorkflowDefinition(
                existing.id(),
                existing.tenantId(),
                existing.name(),
                existing.version(),
                request.description() != null ? request.description() : existing.description(),
                existing.mode(),
                existing.nodes(),
                existing.inputs(),
                existing.outputs(),
                mergeMetadata(existing.metadata(), request.metadata()),
                existing.defaultRetryPolicy(),
                existing.compensationPolicy());
    }

    private WorkflowMetadata mergeMetadata(WorkflowMetadata existing, Map<String, String> update) {
        WorkflowMetadata safeExisting = existing != null ? existing : WorkflowMetadata.system();

        if (update == null) {
            return safeExisting;
        }

        Map<String, String> labels = new HashMap<>(safeExisting.labels());
        labels.putAll(update);
        return new WorkflowMetadata(
                labels,
                safeExisting.annotations(),
                safeExisting.createdAt(),
                safeExisting.createdBy());
    }
}
