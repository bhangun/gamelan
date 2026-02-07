package tech.kayys.gamelan.engine.event;

import java.util.List;

import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.engine.context.WorkflowContext;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;

/**
 * Event Publisher API - Publishes domain events
 */
public interface EventPublisher {

        void publish(
                        String eventType,
                        Object payload,
                        WorkflowContext workflowContext);

        void publishSystem(
                        String eventType,
                        Object payload);

        Uni<Void> publish(List<ExecutionEvent> events);

        Uni<Void> publishRetry(
                        WorkflowRunId runId,
                        NodeId nodeId);
}