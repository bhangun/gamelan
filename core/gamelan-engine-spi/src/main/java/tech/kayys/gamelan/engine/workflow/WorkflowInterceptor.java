package tech.kayys.gamelan.engine.workflow;

import tech.kayys.gamelan.engine.context.WorkflowContext;
import tech.kayys.gamelan.engine.node.NodeContext;
import tech.kayys.gamelan.engine.node.NodeResult;

public interface WorkflowInterceptor {

    default void beforeWorkflow(WorkflowContext ctx) {
    }

    default void beforeNode(NodeContext ctx) {
    }

    default void afterNode(NodeContext ctx, NodeResult result) {
    }

    default void afterWorkflow(WorkflowContext ctx) {
    }

    default void onFailure(NodeContext ctx, Throwable error) {
    }

    default void onEvent(String eventType, Object payload, WorkflowContext ctx) {
    }

    default void onSystemEvent(String eventType, Object payload) {
    }
}