package tech.kayys.gamelan.sdk.client;

import java.util.List;

import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinition;

/**
 * Interface for workflow definition operations.
 * This interface is transport-agnostic, with implementations for REST, gRPC,
 * and Local.
 */
public interface WorkflowDefinitionClient extends AutoCloseable {
    /**
     * Creates a new workflow definition.
     * 
     * @param request the definition details
     * @return a Uni containing the created definition details
     */
    Uni<WorkflowDefinition> createWorkflow(WorkflowDefinition request);

    /**
     * Retrieves a workflow definition by its unique identifier.
     * 
     * @param definitionId the unique ID of the workflow definition
     * @return a Uni containing the definition details
     */
    Uni<WorkflowDefinition> getWorkflow(String definitionId);

    /**
     * Retrieves a workflow definition by its name.
     * 
     * @param name the name of the workflow
     * @return a Uni containing the definition details
     */
    Uni<WorkflowDefinition> getWorkflowByName(String name);

    /**
     * Lists all workflow definitions for the current tenant.
     * 
     * @return a Uni containing a list of definitions
     */
    Uni<List<WorkflowDefinition>> listWorkflows();

    /**
     * Deletes a workflow definition.
     * 
     * @param definitionId the unique ID of the workflow definition
     * @return a Uni representing completion
     */
    Uni<Void> deleteWorkflow(String definitionId);

    @Override
    void close();
}
