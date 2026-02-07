package tech.kayys.gamelan.workflow;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import tech.kayys.gamelan.engine.workflow.dto.CreateWorkflowDefinitionRequest;
import tech.kayys.gamelan.engine.workflow.*;
import tech.kayys.gamelan.core.workflow.WorkflowDefinitionRegistry;
import tech.kayys.gamelan.engine.node.NodeDefinition;
import tech.kayys.gamelan.engine.saga.CompensationPolicy;
import tech.kayys.gamelan.engine.saga.CompensationStrategy;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Workflow definition service
 */
@ApplicationScoped
public class WorkflowDefinitionService implements tech.kayys.gamelan.engine.workflow.WorkflowDefinitionService {

    @Inject
    WorkflowDefinitionRegistry registry;

    public Uni<WorkflowDefinition> create(
            CreateWorkflowDefinitionRequest request,
            tech.kayys.gamelan.engine.tenant.TenantId tenantId) {

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
            List<tech.kayys.gamelan.engine.node.dto.NodeDefinitionDto> dtos) {
        if (dtos == null)
            return List.of();
        return dtos.stream().map(this::mapNodeDefinition).toList();
    }

    private tech.kayys.gamelan.engine.node.NodeDefinition mapNodeDefinition(
            tech.kayys.gamelan.engine.node.dto.NodeDefinitionDto dto) {
        if (dto == null)
            return null;

        List<tech.kayys.gamelan.engine.node.NodeId> dependsOn = dto.dependsOn() != null
                ? dto.dependsOn().stream().map(tech.kayys.gamelan.engine.node.NodeId::of).toList()
                : List.of();

        List<tech.kayys.gamelan.engine.run.Transition> transitions = dto.transitions() != null
                ? dto.transitions().stream().map(this::mapTransition).toList()
                : List.of();

        return new tech.kayys.gamelan.engine.node.NodeDefinition(
                tech.kayys.gamelan.engine.node.NodeId.of(dto.id()),
                dto.name(),
                tech.kayys.gamelan.engine.node.NodeType.valueOf(dto.type()),
                dto.executorType(),
                dto.configuration(),
                dependsOn,
                transitions,
                mapRetryPolicy(dto.retryPolicy()),
                java.time.Duration.ofSeconds(dto.timeoutSeconds() != null ? dto.timeoutSeconds() : 30),
                dto.critical());
    }

    private tech.kayys.gamelan.engine.run.Transition mapTransition(
            tech.kayys.gamelan.engine.transition.dto.TransitionDto dto) {
        if (dto == null)
            return null;

        return new tech.kayys.gamelan.engine.run.Transition(
                dto.targetNodeId() != null ? tech.kayys.gamelan.engine.node.NodeId.of(dto.targetNodeId()) : null,
                dto.condition(),
                tech.kayys.gamelan.engine.run.Transition.TransitionType.valueOf(dto.type()));
    }

    private Map<String, tech.kayys.gamelan.engine.node.InputDefinition> mapInputDefinitions(
            Map<String, tech.kayys.gamelan.engine.io.dto.InputDefinitionDto> dtos) {
        if (dtos == null)
            return Map.of();
        return dtos.entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(
                        java.util.Map.Entry::getKey,
                        entry -> mapInputDefinition(entry.getValue())));
    }

    private tech.kayys.gamelan.engine.node.InputDefinition mapInputDefinition(
            tech.kayys.gamelan.engine.io.dto.InputDefinitionDto dto) {
        if (dto == null)
            return null;

        return new tech.kayys.gamelan.engine.node.InputDefinition(
                dto.name(),
                dto.type(),
                dto.required(),
                dto.defaultValue(),
                dto.description());
    }

    private Map<String, tech.kayys.gamelan.engine.node.OutputDefinition> mapOutputDefinitions(
            Map<String, tech.kayys.gamelan.engine.io.dto.OutputDefinitionDto> dtos) {
        if (dtos == null)
            return Map.of();
        return dtos.entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(
                        java.util.Map.Entry::getKey,
                        entry -> mapOutputDefinition(entry.getValue())));
    }

    private tech.kayys.gamelan.engine.node.OutputDefinition mapOutputDefinition(
            tech.kayys.gamelan.engine.io.dto.OutputDefinitionDto dto) {
        if (dto == null)
            return null;

        return new tech.kayys.gamelan.engine.node.OutputDefinition(
                dto.name(),
                dto.type(),
                dto.description());
    }

    private tech.kayys.gamelan.engine.run.RetryPolicy mapRetryPolicy(
            tech.kayys.gamelan.engine.run.dto.RetryPolicyDto dto) {
        if (dto == null)
            return null;

        return new tech.kayys.gamelan.engine.run.RetryPolicy(
                dto.maxAttempts(),
                java.time.Duration.ofSeconds(dto.initialDelaySeconds()),
                java.time.Duration.ofSeconds(dto.maxDelaySeconds()),
                dto.backoffMultiplier(),
                dto.retryableExceptions());
    }

    private tech.kayys.gamelan.engine.saga.CompensationPolicy mapCompensationPolicy(
            tech.kayys.gamelan.engine.saga.dto.CompensationPolicyDto dto) {
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
        return Uni.createFrom().nullItem();
    }

    public Uni<Void> delete(
            tech.kayys.gamelan.engine.workflow.WorkflowDefinitionId id,
            tech.kayys.gamelan.engine.tenant.TenantId tenantId) {
        return Uni.createFrom().voidItem();
    }
}