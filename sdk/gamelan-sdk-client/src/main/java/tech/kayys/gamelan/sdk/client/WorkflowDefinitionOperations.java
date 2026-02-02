package tech.kayys.gamelan.sdk.client;

import java.util.List;
import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinition;

/**
 * Provides a fluent API for managing workflow definitions.
 * This class is accessed via {@link GamelanClient#workflowDefinitions()}.
 */
public class WorkflowDefinitionOperations {

    private final WorkflowDefinitionClient client;

    WorkflowDefinitionOperations(WorkflowDefinitionClient client) {
        this.client = client;
    }

    /**
     * Initiates the creation of a new workflow definition.
     * 
     * @param name the name of the workflow
     * @return a builder to configure and execute the definition creation
     */
    public WorkflowDefinitionBuilder create(String name) {
        return new WorkflowDefinitionBuilder(client, name);
    }

    /**
     * Retrieves a specific workflow definition by its ID.
     * 
     * @param definitionId the ID of the workflow definition
     * @return a Uni containing the workflow definition
     */
    public Uni<WorkflowDefinition> get(String definitionId) {
        return client.getDefinition(definitionId);
    }

    /**
     * Lists active workflow definitions.
     * 
     * @return a Uni containing a list of active workflow definitions
     */
    public Uni<List<WorkflowDefinition>> list() {
        return client.listDefinitions(true);
    }

    /**
     * Deletes a workflow definition.
     * 
     * @param definitionId the ID of the workflow definition to delete
     * @return a Uni representing the completion of the deletion
     */
    public Uni<Void> delete(String definitionId) {
        return client.deleteDefinition(definitionId);
    }
}