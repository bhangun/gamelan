package tech.kayys.gamelan.engine.run.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import jakarta.validation.constraints.NotBlank;

import java.util.Map;

/**
 * ============================================================================
 * API DATA TRANSFER OBJECTS (DTOs)
 * ============================================================================
 * 
 * Request and response objects for REST API.
 * Separated from domain models for API stability.
 */

// ==================== REQUEST DTOs ====================

/**
 * Request to create a new workflow run
 */
@Schema(description = "Request to create a new workflow run")
public record CreateRunRequest(
        @NotBlank @Schema(description = "Workflow definition ID", required = true, example = "order-processing") String workflowDefinitionId,

        @Schema(description = "Input parameters for the workflow", example = "{\"orderId\": \"ORDER-123\"}") Map<String, Object> inputs,

        @Schema(description = "Labels for categorization") Map<String, String> labels,

        @Schema(description = "Trigger information") TriggerInfo trigger) {
    public CreateRunRequest {
        inputs = inputs != null ? inputs : Map.of();
        labels = labels != null ? labels : Map.of();
    }
}
