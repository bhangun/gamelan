package tech.kayys.silat.dto;

import java.util.Map;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Request to resume a workflow run
 */
@Schema(description = "Request to resume a workflow run")
public record ResumeRunRequest(
        @Schema(description = "Data to merge into workflow context") Map<String, Object> resumeData,

        @Schema(description = "Human task ID if resuming from human task") String humanTaskId) {
    public ResumeRunRequest {
        resumeData = resumeData != null ? resumeData : Map.of();
    }
}
