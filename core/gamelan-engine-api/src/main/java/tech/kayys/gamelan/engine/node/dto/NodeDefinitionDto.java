package tech.kayys.gamelan.engine.node.dto;

import java.util.List;
import java.util.Map;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import com.fasterxml.jackson.annotation.JsonInclude;

import jakarta.validation.constraints.NotBlank;
import tech.kayys.gamelan.engine.node.NodeDefinition;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.run.dto.RetryPolicyDto;
import tech.kayys.gamelan.engine.transition.dto.TransitionDto;

/**
 * Node definition DTO
 */
@Schema(description = "Node definition")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NodeDefinitionDto(
        @NotBlank @Schema(description = "Node ID", required = true) String id,

        @NotBlank @Schema(description = "Node name", required = true) String name,

        @NotBlank @Schema(description = "Node type", required = true) String type,

        @Schema(description = "Executor type") String executorType,

        @Schema(description = "Configuration") Map<String, Object> configuration,

        @Schema(description = "Dependencies (node IDs)") List<String> dependsOn,

        @Schema(description = "Transitions") List<TransitionDto> transitions,

        @Schema(description = "Retry policy") RetryPolicyDto retryPolicy,

        @Schema(description = "Timeout in seconds") Long timeoutSeconds,

        @Schema(description = "Is critical") boolean critical) {
    public static NodeDefinitionDto from(NodeDefinition node) {
        return new NodeDefinitionDto(
                node.id().value(),
                node.name(),
                node.type().name(),
                node.executorType(),
                node.configuration(),
                node.dependsOn().stream().map(NodeId::value).toList(),
                node.transitions().stream().map(TransitionDto::from).toList(),
                node.retryPolicy() != null ? RetryPolicyDto.from(node.retryPolicy()) : null,
                node.timeout() != null ? node.timeout().toSeconds() : null,
                node.critical());
    }
}
