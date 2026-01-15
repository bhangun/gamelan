package tech.kayys.silat.workflow;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import tech.kayys.silat.dto.CreateWorkflowDefinitionRequest;
import tech.kayys.silat.model.*;
import tech.kayys.silat.saga.CompensationPolicy;
import tech.kayys.silat.saga.CompensationStrategy;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Workflow definition service
 */
@ApplicationScoped
public class WorkflowDefinitionService implements tech.kayys.silat.api.workflow.WorkflowDefinitionService {

    @Inject
    WorkflowDefinitionRegistry registry;

    public Uni<WorkflowDefinition> create(
            CreateWorkflowDefinitionRequest request,
            TenantId tenantId) {

        WorkflowDefinition workflow = WorkflowDefinition.builder()
                .id(WorkflowDefinitionId.of(UUID.randomUUID().toString()))
                .tenantId(tenantId)
                .name(request.name())
                .version(request.version())
                .description(request.description())
                .nodes(mapNodeDefinitions(request.nodes()))
                .inputs(mapInputDefinitions(request.inputs()))
                .outputs(mapOutputDefinitions(request.outputs()))
                .defaultRetryPolicy(mapRetryPolicy(request.retryPolicy()))
                .compensationPolicy(mapCompensationPolicy(request.compensationPolicy()))
                .metadata(new WorkflowMetadata(
                        request.metadata() != null ? request.metadata() : Map.of(),
                        Map.of(),
                        Instant.now(),
                        "system"))
                .build();

        return registry.register(workflow, tenantId);
    }

    private List<NodeDefinition> mapNodeDefinitions(
            List<tech.kayys.silat.dto.NodeDefinitionDto> dtos) {
        if (dtos == null)
            return List.of();
        return dtos.stream().map(this::mapNodeDefinition).toList();
    }

    private tech.kayys.silat.model.NodeDefinition mapNodeDefinition(tech.kayys.silat.dto.NodeDefinitionDto dto) {
        if (dto == null)
            return null;

        List<tech.kayys.silat.model.NodeId> dependsOn = dto.dependsOn() != null
                ? dto.dependsOn().stream().map(tech.kayys.silat.model.NodeId::of).toList()
                : List.of();

        List<tech.kayys.silat.model.Transition> transitions = dto.transitions() != null
                ? dto.transitions().stream().map(this::mapTransition).toList()
                : List.of();

        return new tech.kayys.silat.model.NodeDefinition(
                tech.kayys.silat.model.NodeId.of(dto.id()),
                dto.name(),
                tech.kayys.silat.model.NodeType.valueOf(dto.type()),
                dto.executorType(),
                dto.configuration(),
                dependsOn,
                transitions,
                mapRetryPolicy(dto.retryPolicy()),
                java.time.Duration.ofSeconds(dto.timeoutSeconds() != null ? dto.timeoutSeconds() : 30),
                dto.critical());
    }

    private tech.kayys.silat.model.Transition mapTransition(tech.kayys.silat.dto.TransitionDto dto) {
        if (dto == null)
            return null;

        return new tech.kayys.silat.model.Transition(
                dto.targetNodeId() != null ? tech.kayys.silat.model.NodeId.of(dto.targetNodeId()) : null,
                dto.condition(),
                tech.kayys.silat.model.Transition.TransitionType.valueOf(dto.type()));
    }

    private Map<String, InputDefinition> mapInputDefinitions(
            Map<String, tech.kayys.silat.dto.InputDefinitionDto> dtos) {
        if (dtos == null)
            return Map.of();
        return dtos.entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(
                        java.util.Map.Entry::getKey,
                        entry -> mapInputDefinition(entry.getValue())));
    }

    private tech.kayys.silat.model.InputDefinition mapInputDefinition(tech.kayys.silat.dto.InputDefinitionDto dto) {
        if (dto == null)
            return null;

        return new tech.kayys.silat.model.InputDefinition(
                dto.name(),
                dto.type(),
                dto.required(),
                dto.defaultValue(),
                dto.description());
    }

    private Map<String, OutputDefinition> mapOutputDefinitions(
            Map<String, tech.kayys.silat.dto.OutputDefinitionDto> dtos) {
        if (dtos == null)
            return Map.of();
        return dtos.entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(
                        java.util.Map.Entry::getKey,
                        entry -> mapOutputDefinition(entry.getValue())));
    }

    private tech.kayys.silat.model.OutputDefinition mapOutputDefinition(tech.kayys.silat.dto.OutputDefinitionDto dto) {
        if (dto == null)
            return null;

        return new tech.kayys.silat.model.OutputDefinition(
                dto.name(),
                dto.type(),
                dto.description());
    }

    private tech.kayys.silat.model.RetryPolicy mapRetryPolicy(tech.kayys.silat.dto.RetryPolicyDto dto) {
        if (dto == null)
            return null;

        return new tech.kayys.silat.model.RetryPolicy(
                dto.maxAttempts(),
                java.time.Duration.ofSeconds(dto.initialDelaySeconds()),
                java.time.Duration.ofSeconds(dto.maxDelaySeconds()),
                dto.backoffMultiplier(),
                dto.retryableExceptions());
    }

    private tech.kayys.silat.saga.CompensationPolicy mapCompensationPolicy(
            tech.kayys.silat.dto.CompensationPolicyDto dto) {
        if (dto == null)
            return null;

        CompensationStrategy strategy = dto.strategy() != null
                ? CompensationStrategy.valueOf(dto.strategy())
                : CompensationStrategy.SEQUENTIAL;

        return new CompensationPolicy(
                true,
                strategy,
                Duration.ofSeconds(dto.timeoutSeconds()),
                dto.failOnCompensationError(),
                3 // Default max retries
        );
    }

    public Uni<WorkflowDefinition> get(
            tech.kayys.silat.model.WorkflowDefinitionId id,
            TenantId tenantId) {
        return registry.getDefinition(id, tenantId);
    }

    public Uni<List<WorkflowDefinition>> list(
            TenantId tenantId,
            boolean activeOnly) {
        return Uni.createFrom().item(List.of());
    }

    public Uni<WorkflowDefinition> update(
            tech.kayys.silat.model.WorkflowDefinitionId id,
            tech.kayys.silat.dto.UpdateWorkflowDefinitionRequest request,
            TenantId tenantId) {
        return Uni.createFrom().nullItem();
    }

    public Uni<Void> delete(
            tech.kayys.silat.model.WorkflowDefinitionId id,
            TenantId tenantId) {
        return Uni.createFrom().voidItem();
    }
}