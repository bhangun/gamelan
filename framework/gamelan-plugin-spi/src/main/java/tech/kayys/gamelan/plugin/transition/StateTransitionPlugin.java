package tech.kayys.gamelan.plugin.transition;

import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.engine.plugin.GamelanPlugin;
import tech.kayys.gamelan.engine.run.RunStatus;

/**
 * StateTransitionPlugin
 * 
 * Hooks into workflow state transitions to add validation, logging,
 * notifications, or other cross-cutting concerns.
 */
public interface StateTransitionPlugin extends GamelanPlugin {
    
    /**
     * Execution order (lower numbers execute first)
     */
    default int getOrder() {
        return 100;
    }
    
    /**
     * Called before a state transition occurs
     * Can be used to validate or prevent transitions
     * 
     * @return Uni that completes successfully to allow transition,
     *         or fails to prevent transition
     */
    Uni<Void> beforeTransition(TransitionContext context);
    
    /**
     * Called after a state transition has occurred
     */
    Uni<Void> afterTransition(TransitionContext context);
    
    /**
     * State transition context
     */
    interface TransitionContext {
        String runId();
        String tenantId();
        RunStatus fromStatus();
        RunStatus toStatus();
        String reason(); // Optional reason for transition
        long timestamp();
    }
}
