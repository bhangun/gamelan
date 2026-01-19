package tech.kayys.gamelan.sdk.client;

import java.util.List;
import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinition;

/**
 * Workflow definition client interface
 */
interface WorkflowDefinitionClient extends AutoCloseable {
    Uni<WorkflowDefinition> createDefinition(WorkflowDefinition request);

    Uni<WorkflowDefinition> getDefinition(String definitionId);

    Uni<List<WorkflowDefinition>> listDefinitions(boolean activeOnly);

    Uni<Void> deleteDefinition(String definitionId);

    @Override
    void close();
}
