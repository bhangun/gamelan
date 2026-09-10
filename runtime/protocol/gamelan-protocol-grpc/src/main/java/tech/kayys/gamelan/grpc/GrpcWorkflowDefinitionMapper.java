package tech.kayys.gamelan.grpc;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.protobuf.NullValue;
import com.google.protobuf.Struct;
import com.google.protobuf.Timestamp;
import com.google.protobuf.Value;
import com.google.protobuf.util.JsonFormat;
import tech.kayys.gamelan.engine.io.dto.InputDefinitionDto;
import tech.kayys.gamelan.engine.io.dto.OutputDefinitionDto;
import tech.kayys.gamelan.engine.node.InputDefinition;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.node.NodeType;
import tech.kayys.gamelan.engine.node.OutputDefinition;
import tech.kayys.gamelan.engine.node.dto.NodeDefinitionDto;
import tech.kayys.gamelan.engine.run.RetryPolicy;
import tech.kayys.gamelan.engine.run.Transition;
import tech.kayys.gamelan.engine.run.dto.RetryPolicyDto;
import tech.kayys.gamelan.engine.saga.CompensationPolicy;
import tech.kayys.gamelan.engine.saga.CompensationStrategy;
import tech.kayys.gamelan.engine.saga.dto.CompensationPolicyDto;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.transition.dto.TransitionDto;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinition;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinitionId;
import tech.kayys.gamelan.engine.workflow.WorkflowMetadata;
import tech.kayys.gamelan.engine.workflow.WorkflowMode;
import tech.kayys.gamelan.engine.workflow.dto.CreateWorkflowDefinitionRequest;
import tech.kayys.gamelan.engine.workflow.dto.UpdateWorkflowDefinitionRequest;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Lossless-enough mapper for workflow-definition gRPC boundaries.
 */
public final class GrpcWorkflowDefinitionMapper {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private GrpcWorkflowDefinitionMapper() {
    }

    public static tech.kayys.gamelan.grpc.v1.CreateDefinitionRequest toProtoCreateRequest(
            WorkflowDefinition definition,
            String tenantId) {
        tech.kayys.gamelan.grpc.v1.CreateDefinitionRequest.Builder builder =
                tech.kayys.gamelan.grpc.v1.CreateDefinitionRequest.newBuilder()
                        .setTenantId(tenantId != null ? tenantId : definition.tenantId().value())
                        .setName(definition.name())
                        .setVersion(definition.version())
                        .setDescription(nonNull(definition.description()))
                        .addAllNodes(definition.nodes().stream()
                                .map(GrpcWorkflowDefinitionMapper::toProtoNode)
                                .toList())
                        .putAllInputs(toProtoInputs(definition.inputs()))
                        .putAllOutputs(toProtoOutputs(definition.outputs()))
                        .putAllMetadata(definition.metadata() != null ? definition.metadata().labels() : Map.of());

        if (definition.defaultRetryPolicy() != null) {
            builder.setDefaultRetryPolicy(toProtoRetryPolicy(definition.defaultRetryPolicy()));
        }
        if (definition.compensationPolicy() != null) {
            builder.setCompensationPolicy(toProtoCompensationPolicy(definition.compensationPolicy()));
        }
        return builder.build();
    }

    public static CreateWorkflowDefinitionRequest toCreateRequest(
            tech.kayys.gamelan.grpc.v1.CreateDefinitionRequest request) {
        return new CreateWorkflowDefinitionRequest(
                request.getName(),
                request.getVersion(),
                request.getDescription(),
                request.getNodesList().stream().map(GrpcWorkflowDefinitionMapper::toNodeDto).toList(),
                toInputDtos(request.getInputsMap()),
                toOutputDtos(request.getOutputsMap()),
                request.hasDefaultRetryPolicy() ? toRetryPolicyDto(request.getDefaultRetryPolicy()) : null,
                request.hasCompensationPolicy() ? toCompensationPolicyDto(request.getCompensationPolicy()) : null,
                request.getMetadataMap());
    }

    public static UpdateWorkflowDefinitionRequest toUpdateRequest(
            tech.kayys.gamelan.grpc.v1.UpdateDefinitionRequest request) {
        return new UpdateWorkflowDefinitionRequest(
                request.getDescription().isBlank() ? null : request.getDescription(),
                request.hasIsActive() ? request.getIsActive() : null,
                request.getMetadataMap());
    }

    public static tech.kayys.gamelan.grpc.v1.DefinitionResponse toProtoDefinitionResponse(
            WorkflowDefinition definition,
            boolean active) {
        WorkflowMetadata metadata = definition.metadata();
        tech.kayys.gamelan.grpc.v1.DefinitionResponse.Builder builder =
                tech.kayys.gamelan.grpc.v1.DefinitionResponse.newBuilder()
                        .setDefinitionId(definition.id().value())
                        .setTenantId(definition.tenantId().value())
                        .setName(definition.name())
                        .setVersion(definition.version())
                        .setDescription(nonNull(definition.description()))
                        .addAllNodes(definition.nodes().stream()
                                .map(GrpcWorkflowDefinitionMapper::toProtoNode)
                                .toList())
                        .putAllInputs(toProtoInputs(definition.inputs()))
                        .putAllOutputs(toProtoOutputs(definition.outputs()))
                        .setIsActive(active)
                        .putAllMetadata(metadata != null ? metadata.labels() : Map.of());

        if (metadata != null && metadata.createdAt() != null) {
            builder.setCreatedAt(toProtoTimestamp(metadata.createdAt()));
        }
        if (definition.defaultRetryPolicy() != null) {
            builder.setDefaultRetryPolicy(toProtoRetryPolicy(definition.defaultRetryPolicy()));
        }
        if (definition.compensationPolicy() != null) {
            builder.setCompensationPolicy(toProtoCompensationPolicy(definition.compensationPolicy()));
        }
        return builder.build();
    }

    public static WorkflowDefinition toDomainDefinition(
            tech.kayys.gamelan.grpc.v1.DefinitionResponse response,
            String fallbackTenantId) {
        String tenantId = !response.getTenantId().isBlank() ? response.getTenantId() : fallbackTenantId;
        return new WorkflowDefinition(
                WorkflowDefinitionId.of(response.getDefinitionId()),
                TenantId.of(tenantId),
                response.getName(),
                response.getVersion(),
                response.getDescription(),
                WorkflowMode.FLOW,
                response.getNodesList().stream().map(GrpcWorkflowDefinitionMapper::toDomainNode).toList(),
                toDomainInputs(response.getInputsMap()),
                toDomainOutputs(response.getOutputsMap()),
                new WorkflowMetadata(
                        response.getMetadataMap(),
                        Map.of(),
                        response.hasCreatedAt() ? toInstant(response.getCreatedAt()) : Instant.now(),
                        "grpc"),
                response.hasDefaultRetryPolicy() ? toDomainRetryPolicy(response.getDefaultRetryPolicy()) : null,
                response.hasCompensationPolicy() ? toDomainCompensationPolicy(response.getCompensationPolicy()) : null);
    }

    public static WorkflowDefinition toDomainDefinition(
            tech.kayys.gamelan.grpc.v1.CreateDefinitionRequest request,
            TenantId tenantId) {
        return new WorkflowDefinition(
                WorkflowDefinitionId.of(UUID.randomUUID().toString()),
                tenantId,
                request.getName(),
                request.getVersion(),
                request.getDescription(),
                WorkflowMode.FLOW,
                request.getNodesList().stream().map(GrpcWorkflowDefinitionMapper::toDomainNode).toList(),
                toDomainInputs(request.getInputsMap()),
                toDomainOutputs(request.getOutputsMap()),
                WorkflowMetadata.system(request.getMetadataMap()),
                request.hasDefaultRetryPolicy() ? toDomainRetryPolicy(request.getDefaultRetryPolicy()) : null,
                request.hasCompensationPolicy() ? toDomainCompensationPolicy(request.getCompensationPolicy()) : null);
    }

    private static tech.kayys.gamelan.grpc.v1.NodeDefinition toProtoNode(
            tech.kayys.gamelan.engine.node.NodeDefinition node) {
        tech.kayys.gamelan.grpc.v1.NodeDefinition.Builder builder =
                tech.kayys.gamelan.grpc.v1.NodeDefinition.newBuilder()
                        .setId(node.id().value())
                        .setName(node.name())
                        .setType(node.type().name())
                        .setExecutorType(node.executorType())
                        .setConfiguration(mapToStruct(node.configuration()))
                        .addAllDependsOn(node.dependsOn().stream().map(NodeId::value).toList())
                        .addAllTransitions(node.transitions().stream()
                                .map(GrpcWorkflowDefinitionMapper::toProtoTransition)
                                .toList())
                        .setTimeoutSeconds(node.timeout() != null ? node.timeout().toSeconds() : 0)
                        .setCritical(node.critical());
        if (node.retryPolicy() != null) {
            builder.setRetryPolicy(toProtoRetryPolicy(node.retryPolicy()));
        }
        return builder.build();
    }

    private static tech.kayys.gamelan.engine.node.NodeDefinition toDomainNode(
            tech.kayys.gamelan.grpc.v1.NodeDefinition node) {
        return new tech.kayys.gamelan.engine.node.NodeDefinition(
                NodeId.of(node.getId()),
                node.getName(),
                enumValue(NodeType.class, node.getType(), NodeType.EXECUTOR),
                !node.getExecutorType().isBlank() ? node.getExecutorType() : "unspecified",
                structToMap(node.getConfiguration()),
                node.getDependsOnList().stream().map(NodeId::of).toList(),
                node.getTransitionsList().stream().map(GrpcWorkflowDefinitionMapper::toDomainTransition).toList(),
                node.hasRetryPolicy() ? toDomainRetryPolicy(node.getRetryPolicy()) : null,
                Duration.ofSeconds(node.getTimeoutSeconds()),
                node.getCritical());
    }

    private static NodeDefinitionDto toNodeDto(tech.kayys.gamelan.grpc.v1.NodeDefinition node) {
        return new NodeDefinitionDto(
                node.getId(),
                node.getName(),
                node.getType(),
                node.getExecutorType(),
                structToMap(node.getConfiguration()),
                node.getDependsOnList(),
                node.getTransitionsList().stream().map(GrpcWorkflowDefinitionMapper::toTransitionDto).toList(),
                node.hasRetryPolicy() ? toRetryPolicyDto(node.getRetryPolicy()) : null,
                node.getTimeoutSeconds(),
                node.getCritical());
    }

    private static tech.kayys.gamelan.grpc.v1.Transition toProtoTransition(Transition transition) {
        tech.kayys.gamelan.grpc.v1.Transition.Builder builder =
                tech.kayys.gamelan.grpc.v1.Transition.newBuilder()
                        .setCondition(nonNull(transition.condition()))
                        .setType(transition.type() != null ? transition.type().name() : "");
        if (transition.targetNodeId() != null) {
            builder.setTargetNodeId(transition.targetNodeId().value());
        }
        return builder.build();
    }

    private static Transition toDomainTransition(tech.kayys.gamelan.grpc.v1.Transition transition) {
        return new Transition(
                !transition.getTargetNodeId().isBlank() ? NodeId.of(transition.getTargetNodeId()) : null,
                transition.getCondition(),
                enumValue(Transition.TransitionType.class, transition.getType(), Transition.TransitionType.DEFAULT));
    }

    private static TransitionDto toTransitionDto(tech.kayys.gamelan.grpc.v1.Transition transition) {
        return new TransitionDto(
                transition.getTargetNodeId().isBlank() ? null : transition.getTargetNodeId(),
                transition.getCondition(),
                transition.getType());
    }

    private static Map<String, tech.kayys.gamelan.grpc.v1.InputDefinition> toProtoInputs(
            Map<String, InputDefinition> inputs) {
        Map<String, tech.kayys.gamelan.grpc.v1.InputDefinition> result = new LinkedHashMap<>();
        if (inputs != null) {
            inputs.forEach((key, value) -> result.put(key, toProtoInput(value)));
        }
        return result;
    }

    private static tech.kayys.gamelan.grpc.v1.InputDefinition toProtoInput(InputDefinition input) {
        return tech.kayys.gamelan.grpc.v1.InputDefinition.newBuilder()
                .setName(input.name())
                .setType(input.type())
                .setRequired(input.required())
                .setDefaultValue(toProtoValue(input.defaultValue()))
                .setDescription(nonNull(input.description()))
                .build();
    }

    private static Map<String, tech.kayys.gamelan.grpc.v1.InputDefinition> toProtoInputDtos(
            Map<String, InputDefinitionDto> inputs) {
        Map<String, tech.kayys.gamelan.grpc.v1.InputDefinition> result = new LinkedHashMap<>();
        if (inputs != null) {
            inputs.forEach((key, value) -> result.put(key, tech.kayys.gamelan.grpc.v1.InputDefinition.newBuilder()
                    .setName(value.name())
                    .setType(value.type())
                    .setRequired(value.required())
                    .setDefaultValue(toProtoValue(value.defaultValue()))
                    .setDescription(nonNull(value.description()))
                    .build()));
        }
        return result;
    }

    private static Map<String, InputDefinitionDto> toInputDtos(
            Map<String, tech.kayys.gamelan.grpc.v1.InputDefinition> inputs) {
        Map<String, InputDefinitionDto> result = new LinkedHashMap<>();
        inputs.forEach((key, value) -> result.put(key, new InputDefinitionDto(
                value.getName(),
                value.getType(),
                value.getRequired(),
                value.hasDefaultValue() ? fromProtoValue(value.getDefaultValue()) : null,
                value.getDescription())));
        return result;
    }

    private static Map<String, InputDefinition> toDomainInputs(
            Map<String, tech.kayys.gamelan.grpc.v1.InputDefinition> inputs) {
        Map<String, InputDefinition> result = new LinkedHashMap<>();
        inputs.forEach((key, value) -> result.put(key, new InputDefinition(
                value.getName(),
                value.getType(),
                value.getRequired(),
                value.hasDefaultValue() ? fromProtoValue(value.getDefaultValue()) : null,
                value.getDescription())));
        return result;
    }

    private static Map<String, tech.kayys.gamelan.grpc.v1.OutputDefinition> toProtoOutputs(
            Map<String, OutputDefinition> outputs) {
        Map<String, tech.kayys.gamelan.grpc.v1.OutputDefinition> result = new LinkedHashMap<>();
        if (outputs != null) {
            outputs.forEach((key, value) -> result.put(key, tech.kayys.gamelan.grpc.v1.OutputDefinition.newBuilder()
                    .setName(value.name())
                    .setType(value.type())
                    .setDescription(nonNull(value.description()))
                    .build()));
        }
        return result;
    }

    private static Map<String, tech.kayys.gamelan.grpc.v1.OutputDefinition> toProtoOutputDtos(
            Map<String, OutputDefinitionDto> outputs) {
        Map<String, tech.kayys.gamelan.grpc.v1.OutputDefinition> result = new LinkedHashMap<>();
        if (outputs != null) {
            outputs.forEach((key, value) -> result.put(key, tech.kayys.gamelan.grpc.v1.OutputDefinition.newBuilder()
                    .setName(value.name())
                    .setType(value.type())
                    .setDescription(nonNull(value.description()))
                    .build()));
        }
        return result;
    }

    private static Map<String, OutputDefinitionDto> toOutputDtos(
            Map<String, tech.kayys.gamelan.grpc.v1.OutputDefinition> outputs) {
        Map<String, OutputDefinitionDto> result = new LinkedHashMap<>();
        outputs.forEach((key, value) -> result.put(key, new OutputDefinitionDto(
                value.getName(),
                value.getType(),
                value.getDescription())));
        return result;
    }

    private static Map<String, OutputDefinition> toDomainOutputs(
            Map<String, tech.kayys.gamelan.grpc.v1.OutputDefinition> outputs) {
        Map<String, OutputDefinition> result = new LinkedHashMap<>();
        outputs.forEach((key, value) -> result.put(key, new OutputDefinition(
                value.getName(),
                value.getType(),
                value.getDescription())));
        return result;
    }

    public static tech.kayys.gamelan.grpc.v1.CreateDefinitionRequest toProtoCreateRequest(
            CreateWorkflowDefinitionRequest request,
            String tenantId) {
        tech.kayys.gamelan.grpc.v1.CreateDefinitionRequest.Builder builder =
                tech.kayys.gamelan.grpc.v1.CreateDefinitionRequest.newBuilder()
                        .setTenantId(nonNull(tenantId))
                        .setName(request.name())
                        .setVersion(request.version())
                        .setDescription(nonNull(request.description()))
                        .addAllNodes(request.nodes().stream()
                                .map(GrpcWorkflowDefinitionMapper::toProtoNodeDto)
                                .toList())
                        .putAllInputs(toProtoInputDtos(request.inputs()))
                        .putAllOutputs(toProtoOutputDtos(request.outputs()))
                        .putAllMetadata(request.metadata());
        if (request.retryPolicy() != null) {
            builder.setDefaultRetryPolicy(toProtoRetryPolicy(request.retryPolicy()));
        }
        if (request.compensationPolicy() != null) {
            builder.setCompensationPolicy(toProtoCompensationPolicy(request.compensationPolicy()));
        }
        return builder.build();
    }

    private static tech.kayys.gamelan.grpc.v1.NodeDefinition toProtoNodeDto(NodeDefinitionDto node) {
        tech.kayys.gamelan.grpc.v1.NodeDefinition.Builder builder =
                tech.kayys.gamelan.grpc.v1.NodeDefinition.newBuilder()
                        .setId(node.id())
                        .setName(node.name())
                        .setType(node.type())
                        .setExecutorType(nonNull(node.executorType()))
                        .setConfiguration(mapToStruct(node.configuration()))
                        .addAllDependsOn(node.dependsOn())
                        .addAllTransitions(node.transitions().stream()
                                .map(GrpcWorkflowDefinitionMapper::toProtoTransitionDto)
                                .toList())
                        .setTimeoutSeconds(node.timeoutSeconds() != null ? node.timeoutSeconds() : 0)
                        .setCritical(node.critical());
        if (node.retryPolicy() != null) {
            builder.setRetryPolicy(toProtoRetryPolicy(node.retryPolicy()));
        }
        return builder.build();
    }

    private static tech.kayys.gamelan.grpc.v1.Transition toProtoTransitionDto(TransitionDto transition) {
        tech.kayys.gamelan.grpc.v1.Transition.Builder builder =
                tech.kayys.gamelan.grpc.v1.Transition.newBuilder()
                        .setCondition(nonNull(transition.condition()))
                        .setType(nonNull(transition.type()));
        if (transition.targetNodeId() != null && !transition.targetNodeId().isBlank()) {
            builder.setTargetNodeId(transition.targetNodeId());
        }
        return builder.build();
    }

    private static tech.kayys.gamelan.grpc.v1.RetryPolicy toProtoRetryPolicy(RetryPolicy policy) {
        return tech.kayys.gamelan.grpc.v1.RetryPolicy.newBuilder()
                .setMaxAttempts(policy.maxAttempts())
                .setInitialDelaySeconds(policy.initialDelay().toSeconds())
                .setMaxDelaySeconds(policy.maxDelay().toSeconds())
                .setBackoffMultiplier(policy.backoffMultiplier())
                .addAllRetryableExceptions(policy.retryableExceptions())
                .build();
    }

    private static tech.kayys.gamelan.grpc.v1.RetryPolicy toProtoRetryPolicy(RetryPolicyDto policy) {
        return tech.kayys.gamelan.grpc.v1.RetryPolicy.newBuilder()
                .setMaxAttempts(policy.maxAttempts())
                .setInitialDelaySeconds(policy.initialDelaySeconds())
                .setMaxDelaySeconds(policy.maxDelaySeconds())
                .setBackoffMultiplier(policy.backoffMultiplier())
                .addAllRetryableExceptions(policy.retryableExceptions())
                .build();
    }

    private static RetryPolicy toDomainRetryPolicy(tech.kayys.gamelan.grpc.v1.RetryPolicy policy) {
        return new RetryPolicy(
                policy.getMaxAttempts(),
                Duration.ofSeconds(policy.getInitialDelaySeconds()),
                Duration.ofSeconds(policy.getMaxDelaySeconds()),
                policy.getBackoffMultiplier(),
                policy.getRetryableExceptionsList());
    }

    private static RetryPolicyDto toRetryPolicyDto(tech.kayys.gamelan.grpc.v1.RetryPolicy policy) {
        return new RetryPolicyDto(
                policy.getMaxAttempts(),
                policy.getInitialDelaySeconds(),
                policy.getMaxDelaySeconds(),
                policy.getBackoffMultiplier(),
                policy.getRetryableExceptionsList());
    }

    private static tech.kayys.gamelan.grpc.v1.CompensationPolicy toProtoCompensationPolicy(
            CompensationPolicy policy) {
        return tech.kayys.gamelan.grpc.v1.CompensationPolicy.newBuilder()
                .setStrategy(policy.strategy().name())
                .setTimeoutSeconds(policy.timeout().toSeconds())
                .setFailOnCompensationError(policy.failOnCompensationError())
                .setEnabled(policy.enabled())
                .setMaxRetries(policy.maxRetries())
                .build();
    }

    private static tech.kayys.gamelan.grpc.v1.CompensationPolicy toProtoCompensationPolicy(
            CompensationPolicyDto policy) {
        return tech.kayys.gamelan.grpc.v1.CompensationPolicy.newBuilder()
                .setStrategy(policy.strategy())
                .setTimeoutSeconds(policy.timeoutSeconds())
                .setFailOnCompensationError(policy.failOnCompensationError())
                .setEnabled(true)
                .build();
    }

    private static CompensationPolicy toDomainCompensationPolicy(
            tech.kayys.gamelan.grpc.v1.CompensationPolicy policy) {
        if (!policy.getEnabled()) {
            return CompensationPolicy.disabled();
        }
        return new CompensationPolicy(
                true,
                enumValue(CompensationStrategy.class, policy.getStrategy(), CompensationStrategy.SEQUENTIAL),
                Duration.ofSeconds(policy.getTimeoutSeconds()),
                policy.getFailOnCompensationError(),
                policy.getMaxRetries());
    }

    private static CompensationPolicyDto toCompensationPolicyDto(
            tech.kayys.gamelan.grpc.v1.CompensationPolicy policy) {
        if (!policy.getEnabled()) {
            return null;
        }
        return new CompensationPolicyDto(
                !policy.getStrategy().isBlank() ? policy.getStrategy() : CompensationStrategy.SEQUENTIAL.name(),
                policy.getTimeoutSeconds(),
                policy.getFailOnCompensationError());
    }

    private static Struct mapToStruct(Map<String, Object> map) {
        try {
            Struct.Builder builder = Struct.newBuilder();
            JsonFormat.parser().merge(MAPPER.writeValueAsString(map != null ? map : Map.of()), builder);
            return builder.build();
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize gRPC struct payload", e);
        }
    }

    private static Map<String, Object> structToMap(Struct struct) {
        try {
            return MAPPER.readValue(JsonFormat.printer().print(struct), new TypeReference<>() {
            });
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to deserialize gRPC struct payload", e);
        }
    }

    private static Value toProtoValue(Object value) {
        if (value == null) {
            return Value.newBuilder().setNullValue(NullValue.NULL_VALUE).build();
        }
        try {
            Value.Builder builder = Value.newBuilder();
            JsonFormat.parser().merge(MAPPER.writeValueAsString(value), builder);
            return builder.build();
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize gRPC value payload", e);
        }
    }

    private static Object fromProtoValue(Value value) {
        try {
            return MAPPER.readValue(JsonFormat.printer().print(value), Object.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to deserialize gRPC value payload", e);
        }
    }

    private static Timestamp toProtoTimestamp(Instant instant) {
        return Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond())
                .setNanos(instant.getNano())
                .build();
    }

    private static Instant toInstant(Timestamp timestamp) {
        return Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos());
    }

    private static <E extends Enum<E>> E enumValue(Class<E> enumType, String value, E fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return Enum.valueOf(enumType, value.trim().toUpperCase(Locale.ROOT));
    }

    private static String nonNull(String value) {
        return value != null ? value : "";
    }
}
