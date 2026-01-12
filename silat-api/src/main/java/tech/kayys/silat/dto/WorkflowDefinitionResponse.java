package tech.kayys.silat.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import com.fasterxml.jackson.annotation.JsonInclude;

import tech.kayys.silat.model.WorkflowDefinition;



/**
 * Workflow definition response
 */
@Schema(description = "Workflow definition response")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WorkflowDefinitionResponse(
        @Schema(description = "Definition ID") String definitionId,

        @Schema(description = "Name") String name,

        @Schema(description = "Version") String version,

        @Schema(description = "Description") String description,

        @Schema(description = "Node definitions") List<NodeDefinitionDto> nodes,

        @Schema(description = "Input definitions") Map<String, InputDefinitionDto> inputs,

        @Schema(description = "Output definitions") Map<String, OutputDefinitionDto> outputs,

        @Schema(description = "Is active") boolean isActive,

        @Schema(description = "Created timestamp") Instant createdAt,

        @Schema(description = "Metadata") Map<String, String> metadata) {
    public static WorkflowDefinitionResponse from(WorkflowDefinition definition) {
        return new WorkflowDefinitionResponse(
                definition.id().value(),
                definition.name(),
                definition.version(),
                definition.description(),
                definition.nodes().stream()
                        .map(NodeDefinitionDto::from)
                        .toList(),
                null, // simplified
                null, // simplified
                true,
                definition.metadata().createdAt(),
                definition.metadata().labels());
    }
}
