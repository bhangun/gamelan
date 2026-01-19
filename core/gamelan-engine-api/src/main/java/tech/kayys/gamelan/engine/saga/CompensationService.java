package tech.kayys.gamelan.engine.saga;

import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinition;
import tech.kayys.gamelan.engine.workflow.WorkflowRun;

/**
 * Service interface for saga compensation
 */
public interface CompensationService {

    /**
     * Execute compensation for a failed workflow
     */
    Uni<CompensationResult> compensate(WorkflowRun run);

    /**
     * Compensate a specific node
     */
    Uni<CompensationResult> compensateNode(
            WorkflowRun run,
            WorkflowDefinition definition,
            NodeId nodeId);

    /**
     * Check if compensation is needed for a workflow
     */
    boolean needsCompensation(WorkflowRun run);
}
