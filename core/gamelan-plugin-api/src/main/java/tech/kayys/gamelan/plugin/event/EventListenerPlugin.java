package tech.kayys.gamelan.plugin.event;

import tech.kayys.gamelan.engine.plugin.GamelanPlugin;

/**
 * Plugin interface for workflow event listeners
 * 
 * Event listener plugins can react to workflow lifecycle events.
 */
public interface EventListenerPlugin extends GamelanPlugin {

    /**
     * Called when a workflow run is started
     * 
     * @param event the workflow started event
     */
    default void onWorkflowStarted(WorkflowStartedEvent event) {
        // Default: do nothing
    }

    /**
     * Called when a workflow run is completed
     * 
     * @param event the workflow completed event
     */
    default void onWorkflowCompleted(WorkflowCompletedEvent event) {
        // Default: do nothing
    }

    /**
     * Called when a workflow run fails
     * 
     * @param event the workflow failed event
     */
    default void onWorkflowFailed(WorkflowFailedEvent event) {
        // Default: do nothing
    }

    /**
     * Called when a node is executed
     * 
     * @param event the node executed event
     */
    default void onNodeExecuted(NodeExecutedEvent event) {
        // Default: do nothing
    }

    /**
     * Called when a node execution fails
     * 
     * @param event the node failed event
     */
    default void onNodeFailed(NodeFailedEvent event) {
        // Default: do nothing
    }

    /**
     * Workflow started event
     */
    interface WorkflowStartedEvent {
        String runId();

        String definitionId();

        java.time.Instant startedAt();

        java.util.Map<String, Object> inputs();
    }

    /**
     * Workflow completed event
     */
    interface WorkflowCompletedEvent {
        String runId();

        String definitionId();

        java.time.Instant completedAt();

        java.util.Map<String, Object> outputs();
    }

    /**
     * Workflow failed event
     */
    interface WorkflowFailedEvent {
        String runId();

        String definitionId();

        java.time.Instant failedAt();

        String errorMessage();
    }

    /**
     * Node executed event
     */
    interface NodeExecutedEvent {
        String runId();

        String nodeId();

        String nodeType();

        java.time.Instant executedAt();

        java.util.Map<String, Object> outputs();
    }

    /**
     * Node failed event
     */
    interface NodeFailedEvent {
        String runId();

        String nodeId();

        String nodeType();

        java.time.Instant failedAt();

        String errorMessage();
    }
}
