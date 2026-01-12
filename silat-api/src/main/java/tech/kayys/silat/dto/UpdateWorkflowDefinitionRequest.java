package tech.kayys.silat.dto;

import java.util.Map;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Request to update a workflow definition
 */
@Schema(description = "Request to update a workflow definition")
public record UpdateWorkflowDefinitionRequest(
        @Schema(description = "Description") String description,

        @Schema(description = "Active status") Boolean isActive,

        @Schema(description = "Metadata") Map<String, String> metadata) {
}
