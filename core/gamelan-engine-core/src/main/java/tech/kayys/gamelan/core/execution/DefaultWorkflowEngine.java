package tech.kayys.gamelan.core.execution;

import java.util.Collection;
import java.util.concurrent.CompletionStage;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.core.engine.WorkflowEngine;
import tech.kayys.gamelan.engine.context.EngineContext;
import tech.kayys.gamelan.engine.executor.ExecutorDispatcher;
import tech.kayys.gamelan.engine.extension.ExtensionRegistry;
import tech.kayys.gamelan.engine.node.NodeContext;
import tech.kayys.gamelan.engine.node.NodeExecutionContext;
import tech.kayys.gamelan.engine.node.NodeResult;
import tech.kayys.gamelan.engine.workflow.WorkflowInterceptor;

@ApplicationScoped
public class DefaultWorkflowEngine implements WorkflowEngine {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultWorkflowEngine.class);

    private EngineContext engineContext;

    @Inject
    ExtensionRegistry extensionRegistry;

    @Override
    public void initialize(EngineContext context) {
        this.engineContext = context;
    }

    @Override
    public Uni<NodeResult> executeNode(NodeContext nodeContext, NodeExecutionContext executionContext) {
        Collection<WorkflowInterceptor> interceptors = extensionRegistry != null
                ? extensionRegistry.interceptors()
                : java.util.Collections.emptyList();

        // 1. Run beforeNode
        interceptors.forEach(i -> {
            try {
                i.beforeNode(nodeContext);
            } catch (Exception e) {
                LOG.error("Interceptor error in beforeNode", e);
            }
        });

        // 2. Real Execution
        LOG.info("Dispatching node: {} (type: {})", nodeContext.nodeId().value(), nodeContext.nodeType());
        return performExecution(nodeContext, executionContext)
                .chain(nodeResult -> {
                    // 3. Run afterNode
                    interceptors.forEach(i -> {
                        try {
                            i.afterNode(nodeContext, nodeResult);
                        } catch (Exception e) {
                            LOG.error("Interceptor error in afterNode", e);
                        }
                    });
                    return Uni.createFrom().item(nodeResult);
                })
                .onFailure().recoverWithUni(t -> {
                    // 4. Handle onFailure
                    LOG.error("Error executing node: {}", nodeContext.nodeId().value(), t);
                    interceptors.forEach(i -> {
                        try {
                            i.onFailure(nodeContext, t);
                        } catch (Exception e) {
                            LOG.error("Interceptor error in onFailure", e);
                        }
                    });
                    return Uni.createFrom().item(NodeResult.failure(t.getMessage()));
                });
    }

    private Uni<NodeResult> performExecution(NodeContext nodeContext, NodeExecutionContext executionContext) {
        ExecutorDispatcher dispatcher = engineContext != null ? engineContext.executorDispatcher() : null;
        if (dispatcher == null) {
            return Uni.createFrom().failure(new IllegalStateException("ExecutorDispatcher is not available"));
        }

        CompletionStage<NodeResult> future = dispatcher.dispatch(nodeContext, executionContext);
        return Uni.createFrom().completionStage(future);
    }
}
