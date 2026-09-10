package tech.kayys.gamelan.plugin.interceptor;

import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.engine.plugin.GamelanPlugin;
import tech.kayys.gamelan.engine.run.RunStatus;

import java.util.Map;

/**
 * WorkflowInterceptorPlugin
 * 
 * Intercepts workflow-level lifecycle events (start, complete, fail, etc.)
 * Allows plugins to add cross-cutting concerns at the workflow level.
 */
public interface WorkflowInterceptorPlugin extends GamelanPlugin {
    
    /**
     * Execution order (lower numbers execute first)
     */
    default int getOrder() {
        return 100;
    }
    
    /**
     * Called before a workflow starts execution
     */
    Uni<Void> beforeWorkflowStart(WorkflowContext context);
    
    /**
     * Called after a workflow completes successfully
     */
    Uni<Void> afterWorkflowComplete(WorkflowContext context, Map<String, Object> outputs);
    
    /**
     * Called when a workflow fails
     */
    Uni<Void> onWorkflowFailure(WorkflowContext context, Throwable error);
    
    /**
     * Called when a workflow is cancelled
     */
    Uni<Void> onWorkflowCancelled(WorkflowContext context, String reason);
    
    /**
     * Called when a workflow is suspended
     */
    Uni<Void> onWorkflowSuspended(WorkflowContext context, String reason);
    
    /**
     * Called when a workflow resumes from suspension
     */
    Uni<Void> onWorkflowResumed(WorkflowContext context, Map<String, Object> resumeData);
    
    /**
     * Workflow execution context
     */
    interface WorkflowContext {
        String runId();
        String definitionId();
        String tenantId();
        RunStatus currentStatus();
        Map<String, Object> variables();
        long executionTimeMs();
    }
}
