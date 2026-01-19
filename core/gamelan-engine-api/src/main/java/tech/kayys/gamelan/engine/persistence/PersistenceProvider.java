package tech.kayys.gamelan.engine.persistence;

import java.util.Optional;

import tech.kayys.gamelan.engine.context.WorkflowContext;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.node.NodeResult;
import tech.kayys.gamelan.engine.signal.SignalContext;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;

public interface PersistenceProvider {

        void saveWorkflow(WorkflowContext workflow);

        Optional<WorkflowContext> loadWorkflow(WorkflowRunId runId);

        void appendEvent(
                        WorkflowRunId runId,
                        String eventType,
                        Object payload);

        void saveNodeResult(
                        WorkflowRunId runId,
                        NodeId nodeId,
                        NodeResult result);

        void saveSignal(
                        WorkflowRunId runId,
                        SignalContext signal);
}
