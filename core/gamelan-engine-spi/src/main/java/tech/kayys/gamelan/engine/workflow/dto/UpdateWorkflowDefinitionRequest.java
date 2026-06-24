package tech.kayys.gamelan.engine.workflow.dto;

import java.util.Map;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import tech.kayys.gamelan.engine.payload.ExecutionPayloads;

/**
 * Request to update a workflow definition
 */
@Schema(description = "Request to update a workflow definition")
public record UpdateWorkflowDefinitionRequest(
        @Schema(description = "Description") String description,

        @Schema(description = "Active status") Boolean isActive,

        @Schema(description = "Metadata") Map<String, String> metadata) {
    public UpdateWorkflowDefinitionRequest {
        metadata = ExecutionPayloads.immutableMapCopy(metadata);
    }
}
