package tech.kayys.silat.model;

import java.time.Instant;
import java.util.Map;

/**
 * Workflow Metadata
 */
public record WorkflowMetadata(
        Map<String, String> labels,
        Map<String, String> annotations,
        Instant createdAt,
        String createdBy) {
    public WorkflowMetadata {
        labels = Map.copyOf(labels);
        annotations = Map.copyOf(annotations);
    }
}