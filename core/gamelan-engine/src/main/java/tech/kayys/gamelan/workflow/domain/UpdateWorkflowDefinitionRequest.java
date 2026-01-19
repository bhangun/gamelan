package tech.kayys.gamelan.workflow.domain;

import java.util.Map;

/**
 * Domain model for updating a workflow definition
 */
public record UpdateWorkflowDefinitionRequest(
        String description,
        Boolean isActive,
        Map<String, String> metadata) {
}