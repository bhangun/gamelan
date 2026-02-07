package tech.kayys.gamelan.dag;

import java.util.List;

import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinition;

/**
 * Service interface for DAG scheduling helpers.
 */
public interface DagSchedulerService {
    List<NodeId> orderReadyNodes(WorkflowDefinition definition, List<NodeId> readyNodes);
}
