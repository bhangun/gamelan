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
        Collection<WorkflowInterceptor> interceptors = resolveInterceptors();
        var execInterceptors = resolveExecutionInterceptors();

        tech.kayys.gamelan.plugin.interceptor.ExecutionInterceptorPlugin.TaskContext taskCtx =
                new tech.kayys.gamelan.plugin.interceptor.ExecutionInterceptorPlugin.TaskContext() {
                    public String runId() { return nodeContext.nodeId().value(); }
                    public String nodeId() { return nodeContext.nodeId().value(); }
                    public String nodeType() { return nodeContext.nodeType(); }
                    public java.util.Map<String, Object> inputs() { return nodeContext.input(); }
                    public int attempt() { return (int) nodeContext.metadata().getOrDefault("attempt", 1); }
                };

        // 1. beforeNode (WorkflowInterceptor) + beforeExecution (ExecutionInterceptorPlugin)
        interceptors.forEach(i -> { try { i.beforeNode(nodeContext); } catch (Exception e) { LOG.error("beforeNode error", e); } });
        for (var ei : execInterceptors) {
            try { ei.beforeExecution(taskCtx).await().indefinitely(); } catch (Exception e) { LOG.error("beforeExecution error", e); }
        }

        // 2. Execute
        LOG.info("Dispatching node: {} (type: {})", nodeContext.nodeId().value(), nodeContext.nodeType());
        return performExecution(nodeContext, executionContext)
                .chain(nodeResult -> {
                    if (engineContext != null && engineContext.persistence() != null) {
                        try {
                            tech.kayys.gamelan.engine.node.NodeExecutionSnapshot snapshot = new tech.kayys.gamelan.engine.node.NodeExecutionSnapshot(
                                    nodeContext.nodeId().value(),
                                    nodeResult.success() ? "COMPLETED" : "FAILED",
                                    (int) nodeContext.metadata().getOrDefault("attempt", 1),
                                    null, null,
                                    nodeResult.output() instanceof java.util.Map ? (java.util.Map<String, Object>) nodeResult.output() : null,
                                    null);
                            engineContext.persistence().updateNodeExecution(executionContext.workflow().runId(), nodeContext.nodeId(), snapshot);
                            if (nodeResult.output() instanceof java.util.Map<?, ?> outputMap && !outputMap.isEmpty()) {
                                ((java.util.Map<String, Object>) outputMap).forEach((k, v) ->
                                        engineContext.persistence().updateContextVariable(executionContext.workflow().runId(), k, v));
                            }
                        } catch (Exception e) { LOG.error("Failed surgical update", e); }
                    }

                    // 3. afterExecution (reverse order) + afterNode
                    tech.kayys.gamelan.plugin.interceptor.ExecutionInterceptorPlugin.ExecutionResult execResult =
                            new tech.kayys.gamelan.plugin.interceptor.ExecutionInterceptorPlugin.ExecutionResult() {
                                public boolean isSuccess() { return nodeResult.success(); }
                                public java.util.Map<String, Object> outputs() { return nodeResult.output() instanceof java.util.Map ? (java.util.Map<String, Object>) nodeResult.output() : java.util.Map.of(); }
                                public String errorMessage() { return nodeResult.success() ? null : String.valueOf(nodeResult.output()); }
                            };
                    var reversed = new java.util.ArrayList<>(execInterceptors);
                    java.util.Collections.reverse(reversed);
                    for (var ei : reversed) {
                        try { ei.afterExecution(taskCtx, execResult).await().indefinitely(); } catch (Exception e) { LOG.error("afterExecution error", e); }
                    }
                    interceptors.forEach(i -> { try { i.afterNode(nodeContext, nodeResult); } catch (Exception e) { LOG.error("afterNode error", e); } });
                    return Uni.createFrom().item(nodeResult);
                })
                .onFailure().recoverWithUni(t -> {
                    LOG.error("Error executing node: {}", nodeContext.nodeId().value(), t);
                    interceptors.forEach(i -> { try { i.onFailure(nodeContext, t); } catch (Exception e) { LOG.error("onFailure error", e); } });
                    return Uni.createFrom().item(NodeResult.failure(t.getMessage()));
                });
    }

    private Collection<WorkflowInterceptor> resolveInterceptors() {
        if (extensionRegistry != null) {
            return extensionRegistry.interceptors();
        }
        return java.util.Collections.emptyList();
    }

    @SuppressWarnings("unchecked")
    private java.util.List<tech.kayys.gamelan.plugin.interceptor.ExecutionInterceptorPlugin> resolveExecutionInterceptors() {
        if (engineContext == null || engineContext.pluginRegistry() == null) {
            return java.util.Collections.emptyList();
        }
        return engineContext.pluginRegistry().getAllPlugins().values().stream()
                .map(lp -> lp.getPlugin())
                .filter(p -> p instanceof tech.kayys.gamelan.plugin.interceptor.ExecutionInterceptorPlugin)
                .map(p -> (tech.kayys.gamelan.plugin.interceptor.ExecutionInterceptorPlugin) p)
                .sorted(java.util.Comparator.comparingInt(
                        tech.kayys.gamelan.plugin.interceptor.ExecutionInterceptorPlugin::getOrder))
                .toList();
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
