package tech.kayys.gamelan.engine.workflow;

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
    private static final String SYSTEM_USER = "system";

    public WorkflowMetadata {
        labels = labels != null ? Map.copyOf(labels) : Map.of();
        annotations = annotations != null ? Map.copyOf(annotations) : Map.of();
        createdAt = createdAt != null ? createdAt : Instant.now();
        createdBy = createdBy != null && !createdBy.isBlank() ? createdBy : SYSTEM_USER;
    }

    public static WorkflowMetadata system() {
        return new WorkflowMetadata(Map.of(), Map.of(), Instant.now(), SYSTEM_USER);
    }

    public static WorkflowMetadata system(Map<String, String> labels) {
        return new WorkflowMetadata(labels, Map.of(), Instant.now(), SYSTEM_USER);
    }
}
