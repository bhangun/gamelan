package tech.kayys.silat.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Workflow run response
 */
@Schema(description = "Workflow run response")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RunResponse(
        @Schema(description = "Run ID", example = "550e8400-e29b-41d4-a716-446655440000") String runId,

        @Schema(description = "Tenant ID", example = "acme-corp") String tenantId,

        @Schema(description = "Workflow definition ID", example = "order-processing") String workflowDefinitionId,

        @Schema(description = "Workflow version", example = "1.0.0") String workflowVersion,

        @Schema(description = "Current status", example = "RUNNING") String status,

        @Schema(description = "Context variables") Map<String, Object> variables,

        @Schema(description = "Node execution states") Map<String, NodeExecutionDto> nodeExecutions,

        @Schema(description = "Execution path") List<String> executionPath,

        @Schema(description = "Created timestamp") Instant createdAt,

        @Schema(description = "Started timestamp") Instant startedAt,

        @Schema(description = "Completed timestamp") Instant completedAt,

        @Schema(description = "Duration in milliseconds") Long durationMs,

        @Schema(description = "Labels") Map<String, String> labels,

        @Schema(description = "Metadata") Map<String, String> metadata) {
}