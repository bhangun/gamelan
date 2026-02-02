package tech.kayys.gamelan.sdk.client;

import java.util.List;
import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinition;

/**
 * Interface for workflow definition operations.
 */
public interface WorkflowDefinitionClient extends AutoCloseable {
    /**
     * Creates a new workflow definition.
     * 
     * @param request the definition details
     * @return a Uni containing the created definition
     */
    Uni<WorkflowDefinition> createDefinition(WorkflowDefinition request);

    /**
     * Retrieves a workflow definition by its ID.
     * 
     * @param definitionId the unique ID of the workflow definition
     * @return a Uni containing the definition
     */
    Uni<WorkflowDefinition> getDefinition(String definitionId);

    /**
     * Lists workflow definitions.
     * 
     * @param activeOnly if true, only retrieves active definitions
     * @return a Uni containing a list of definitions
     */
    Uni<List<WorkflowDefinition>> listDefinitions(boolean activeOnly);

    /**
     * Deletes a workflow definition.
     * 
     * @param definitionId the unique ID of the workflow definition
     * @return a Uni representing completion
     */
    Uni<Void> deleteDefinition(String definitionId);

    @Override
    void close();
}
