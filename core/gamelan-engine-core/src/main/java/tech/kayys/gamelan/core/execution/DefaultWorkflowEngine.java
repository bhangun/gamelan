package tech.kayys.gamelan.core.execution;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.core.engine.WorkflowEngine;
import tech.kayys.gamelan.engine.context.EngineContext;
import tech.kayys.gamelan.engine.context.WorkflowContext;
import tech.kayys.gamelan.engine.error.ErrorSnapshot;
import tech.kayys.gamelan.engine.error.ErrorInfo;
import tech.kayys.gamelan.engine.event.EventPublisher;
import tech.kayys.gamelan.engine.event.ExecutionEvent;
import tech.kayys.gamelan.engine.event.NodeCompletedEvent;
import tech.kayys.gamelan.engine.event.NodeFailedEvent;
import tech.kayys.gamelan.engine.event.NodeStartedEvent;
import tech.kayys.gamelan.engine.executor.ExecutorDispatcher;
import tech.kayys.gamelan.engine.extension.ExtensionRegistry;
import tech.kayys.gamelan.engine.node.NodeContext;
import tech.kayys.gamelan.engine.node.NodeExecutionContext;
import tech.kayys.gamelan.engine.node.NodeExecutionSnapshot;
import tech.kayys.gamelan.engine.node.NodeResult;
import tech.kayys.gamelan.engine.node.NodeTypeHandler;
import tech.kayys.gamelan.engine.persistence.PersistenceProvider;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;
import tech.kayys.gamelan.engine.workflow.WorkflowInterceptor;
import tech.kayys.gamelan.plugin.interceptor.ExecutionInterceptorPlugin;

@ApplicationScoped
public class DefaultWorkflowEngine implements WorkflowEngine {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultWorkflowEngine.class);
    private static final String FAIL_ON_BEFORE_INTERCEPTOR_ERROR =
            "gamelan.engine.execution.interceptors.before.fail-on-error";

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
        String runId = resolveRunId(executionContext, nodeContext);

        ExecutionInterceptorPlugin.TaskContext taskCtx =
                new ExecutionInterceptorPlugin.TaskContext() {
                    public String runId() { return runId; }
                    public String nodeId() { return nodeContext.nodeId().value(); }
                    public String nodeType() { return nodeContext.nodeType(); }
                    public Map<String, Object> inputs() { return nodeContext.input(); }
                    public int attempt() { return (int) nodeContext.metadata().getOrDefault("attempt", 1); }
                };

        return runBeforeExecutionHooks(nodeContext, taskCtx, execInterceptors, interceptors)
                .chain(() -> publishNodeStarted(nodeContext, executionContext))
                .invoke(() -> LOG.info("Dispatching node: {} (type: {})", nodeContext.nodeId().value(),
                        nodeContext.nodeType()))
                .chain(() -> performExecution(nodeContext, executionContext))
                .chain(nodeResult -> persistNodeResult(nodeContext, executionContext, nodeResult, null)
                        .chain(() -> publishNodeFinished(nodeContext, executionContext, nodeResult, null))
                        .chain(() -> runAfterExecutionHooks(nodeContext, nodeResult, taskCtx, execInterceptors,
                                interceptors))
                        .replaceWith(nodeResult))
                .onFailure().recoverWithUni(t -> recoverExecutionFailure(nodeContext, executionContext,
                        taskCtx, execInterceptors, interceptors, t));
    }

    private Collection<WorkflowInterceptor> resolveInterceptors() {
        if (extensionRegistry != null) {
            return extensionRegistry.interceptors();
        }
        return Collections.emptyList();
    }

    private List<ExecutionInterceptorPlugin> resolveExecutionInterceptors() {
        if (engineContext == null || engineContext.pluginRegistry() == null) {
            return Collections.emptyList();
        }
        return engineContext.pluginRegistry().getAllPlugins().values().stream()
                .map(lp -> lp.getPlugin())
                .filter(p -> p instanceof ExecutionInterceptorPlugin)
                .map(p -> (ExecutionInterceptorPlugin) p)
                .sorted(java.util.Comparator.comparingInt(ExecutionInterceptorPlugin::getOrder))
                .toList();
    }

    private Uni<NodeResult> performExecution(NodeContext nodeContext, NodeExecutionContext executionContext) {
        NodeTypeHandler handler = extensionRegistry != null ? extensionRegistry.nodeType(nodeContext.nodeType()) : null;
        if (handler != null) {
            return Uni.createFrom().item(() -> handler.execute(executionContext));
        }

        ExecutorDispatcher dispatcher = engineContext != null ? engineContext.executorDispatcher() : null;
        if (dispatcher == null) {
            return Uni.createFrom().failure(new IllegalStateException("ExecutorDispatcher is not available"));
        }

        CompletionStage<NodeResult> future = dispatcher.dispatch(nodeContext, executionContext);
        return Uni.createFrom().completionStage(future);
    }

    private Uni<Void> persistNodeResult(NodeContext nodeContext, NodeExecutionContext executionContext,
            NodeResult nodeResult, Throwable failure) {
        PersistenceProvider persistence = engineContext != null ? engineContext.persistence() : null;
        if (persistence == null) {
            return Uni.createFrom().voidItem();
        }

        return Uni.createFrom().voidItem().invoke(() -> {
            WorkflowRunId runId = requireRunId(executionContext);
            Map<String, Object> output = outputMap(nodeResult);
            NodeExecutionSnapshot snapshot = new NodeExecutionSnapshot(
                    nodeContext.nodeId().value(),
                    nodeResult.success() ? "COMPLETED" : "FAILED",
                    (int) nodeContext.metadata().getOrDefault("attempt", 1),
                    null,
                    null,
                    null,
                    output.isEmpty() ? null : output,
                    errorSnapshot(nodeResult, failure));
            persistence.updateNodeExecution(runId, nodeContext.nodeId(), snapshot);
            if (nodeResult.success()) {
                output.forEach((key, value) -> persistence.updateContextVariable(runId, key, value));
            }
        });
    }

    private Uni<NodeResult> recoverExecutionFailure(
            NodeContext nodeContext,
            NodeExecutionContext executionContext,
            ExecutionInterceptorPlugin.TaskContext taskCtx,
            List<ExecutionInterceptorPlugin> execInterceptors,
            Collection<WorkflowInterceptor> interceptors,
            Throwable failure) {
        LOG.error("Error executing node: {}", nodeContext.nodeId().value(), failure);

        NodeResult failureResult = NodeResult.failure(failureMessage(failure));
        return runExecutionErrorHooks(taskCtx, execInterceptors, failure)
                .invoke(() -> runWorkflowFailureHooks(nodeContext, interceptors, failure))
                .chain(() -> persistNodeResult(nodeContext, executionContext, failureResult, failure))
                .onFailure().invoke(persistError -> LOG.error(
                        "Failed to persist failed node execution: {}", nodeContext.nodeId().value(), persistError))
                .onFailure().recoverWithItem((Void) null)
                .chain(() -> publishNodeFinished(nodeContext, executionContext, failureResult, failure))
                .replaceWith(failureResult);
    }

    private Uni<Void> publishNodeStarted(NodeContext nodeContext, NodeExecutionContext executionContext) {
        WorkflowRunId runId = optionalRunId(executionContext);
        if (runId == null) {
            return Uni.createFrom().voidItem();
        }
        return publishExecutionEvent(new NodeStartedEvent(
                UUID.randomUUID().toString(),
                runId,
                nodeContext.nodeId(),
                attempt(nodeContext),
                now()));
    }

    private Uni<Void> publishNodeFinished(
            NodeContext nodeContext,
            NodeExecutionContext executionContext,
            NodeResult nodeResult,
            Throwable failure) {
        WorkflowRunId runId = optionalRunId(executionContext);
        if (runId == null) {
            return Uni.createFrom().voidItem();
        }
        ExecutionEvent event = nodeResult.success()
                ? new NodeCompletedEvent(
                        UUID.randomUUID().toString(),
                        runId,
                        nodeContext.nodeId(),
                        attempt(nodeContext),
                        outputMap(nodeResult),
                        now())
                : new NodeFailedEvent(
                        UUID.randomUUID().toString(),
                        runId,
                        nodeContext.nodeId(),
                        attempt(nodeContext),
                        errorInfo(nodeResult, failure),
                        false,
                        now());
        return publishExecutionEvent(event);
    }

    private Uni<Void> publishExecutionEvent(ExecutionEvent event) {
        EventPublisher eventPublisher = engineContext != null ? engineContext.eventPublisher() : null;
        if (eventPublisher == null) {
            return Uni.createFrom().voidItem();
        }
        return eventPublisher.publish(List.of(event))
                .onFailure().invoke(error -> LOG.error(
                        "Failed to publish execution event {} for run {}",
                        event.eventType(), event.runId().value(), error))
                .onFailure().recoverWithItem((Void) null);
    }

    private Uni<Void> runBeforeExecutionHooks(
            NodeContext nodeContext,
            ExecutionInterceptorPlugin.TaskContext taskCtx,
            List<ExecutionInterceptorPlugin> execInterceptors,
            Collection<WorkflowInterceptor> interceptors) {
        return Uni.createFrom().voidItem()
                .invoke(() -> runWorkflowBeforeHooks(nodeContext, interceptors))
                .chain(() -> runExecutionHooks(execInterceptors,
                        interceptor -> interceptor.beforeExecution(taskCtx),
                        "beforeExecution",
                        failOnBeforeInterceptorError()));
    }

    private Uni<Void> runExecutionErrorHooks(
            ExecutionInterceptorPlugin.TaskContext taskCtx,
            List<ExecutionInterceptorPlugin> execInterceptors,
            Throwable failure) {
        return runExecutionHooks(reversed(execInterceptors),
                interceptor -> interceptor.onError(taskCtx, failure),
                "onError",
                false);
    }

    private Uni<Void> runAfterExecutionHooks(
            NodeContext nodeContext,
            NodeResult nodeResult,
            ExecutionInterceptorPlugin.TaskContext taskCtx,
            List<ExecutionInterceptorPlugin> execInterceptors,
            Collection<WorkflowInterceptor> interceptors) {
        ExecutionInterceptorPlugin.ExecutionResult execResult =
                new ExecutionInterceptorPlugin.ExecutionResult() {
                    public boolean isSuccess() { return nodeResult.success(); }
                    public Map<String, Object> outputs() { return outputMap(nodeResult); }
                    public String errorMessage() { return nodeResult.success() ? null : String.valueOf(nodeResult.output()); }
                };
        return runExecutionHooks(reversed(execInterceptors),
                interceptor -> interceptor.afterExecution(taskCtx, execResult),
                "afterExecution",
                false)
                .invoke(() -> runWorkflowAfterHooks(nodeContext, nodeResult, interceptors));
    }

    private Uni<Void> runExecutionHooks(
            List<ExecutionInterceptorPlugin> execInterceptors,
            Function<ExecutionInterceptorPlugin, Uni<Void>> hook,
            String hookName,
            boolean failOnError) {
        Uni<Void> chain = Uni.createFrom().voidItem();
        for (ExecutionInterceptorPlugin interceptor : execInterceptors) {
            chain = chain.chain(() -> runExecutionHook(interceptor, hook, hookName, failOnError));
        }
        return chain;
    }

    private Uni<Void> runExecutionHook(
            ExecutionInterceptorPlugin interceptor,
            Function<ExecutionInterceptorPlugin, Uni<Void>> hook,
            String hookName,
            boolean failOnError) {
        Uni<Void> result;
        try {
            result = hook.apply(interceptor);
        } catch (Exception e) {
            LOG.error("{} error", hookName, e);
            return failOnError ? Uni.createFrom().failure(e) : Uni.createFrom().voidItem();
        }
        if (result == null) {
            LOG.error("{} returned null Uni", hookName);
            var error = new IllegalStateException(hookName + " returned null Uni");
            return failOnError ? Uni.createFrom().failure(error) : Uni.createFrom().voidItem();
        }
        return result
                .onFailure().invoke(e -> LOG.error("{} error", hookName, e))
                .onFailure().recoverWithUni(e -> failOnError
                        ? Uni.createFrom().failure(e)
                        : Uni.createFrom().voidItem());
    }

    private boolean failOnBeforeInterceptorError() {
        if (engineContext == null || engineContext.configuration() == null) {
            return false;
        }
        return engineContext.configuration()
                .get(FAIL_ON_BEFORE_INTERCEPTOR_ERROR, Boolean.class)
                .orElse(false);
    }

    private List<ExecutionInterceptorPlugin> reversed(List<ExecutionInterceptorPlugin> execInterceptors) {
        var reversed = new ArrayList<>(execInterceptors);
        Collections.reverse(reversed);
        return reversed;
    }

    private void runWorkflowBeforeHooks(NodeContext nodeContext, Collection<WorkflowInterceptor> interceptors) {
        interceptors.forEach(i -> {
            try {
                i.beforeNode(nodeContext);
            } catch (Exception e) {
                LOG.error("beforeNode error", e);
            }
        });
    }

    private void runWorkflowAfterHooks(
            NodeContext nodeContext,
            NodeResult nodeResult,
            Collection<WorkflowInterceptor> interceptors) {
        interceptors.forEach(i -> {
            try {
                i.afterNode(nodeContext, nodeResult);
            } catch (Exception e) {
                LOG.error("afterNode error", e);
            }
        });
    }

    private void runWorkflowFailureHooks(
            NodeContext nodeContext,
            Collection<WorkflowInterceptor> interceptors,
            Throwable failure) {
        interceptors.forEach(i -> {
            try {
                i.onFailure(nodeContext, failure);
            } catch (Exception e) {
                LOG.error("onFailure error", e);
            }
        });
    }

    private WorkflowRunId requireRunId(NodeExecutionContext executionContext) {
        WorkflowContext workflow = executionContext.workflow();
        if (workflow == null || workflow.runId() == null) {
            throw new IllegalStateException("Workflow run context is required for persistence updates");
        }
        return workflow.runId();
    }

    private WorkflowRunId optionalRunId(NodeExecutionContext executionContext) {
        WorkflowContext workflow = executionContext.workflow();
        return workflow != null ? workflow.runId() : null;
    }

    private String resolveRunId(NodeExecutionContext executionContext, NodeContext nodeContext) {
        WorkflowContext workflow = executionContext.workflow();
        if (workflow != null && workflow.runId() != null) {
            return workflow.runId().value();
        }
        return nodeContext.nodeId().value();
    }

    private int attempt(NodeContext nodeContext) {
        return (int) nodeContext.metadata().getOrDefault("attempt", 1);
    }

    private Map<String, Object> outputMap(NodeResult nodeResult) {
        if (!(nodeResult.output() instanceof Map<?, ?> rawOutput) || rawOutput.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> output = new LinkedHashMap<>();
        rawOutput.forEach((key, value) -> {
            if (key instanceof String stringKey) {
                output.put(stringKey, value);
            } else {
                LOG.warn("Ignoring non-string node output key during context persistence: {}", key);
            }
        });
        return output;
    }

    private ErrorSnapshot errorSnapshot(NodeResult nodeResult, Throwable failure) {
        if (nodeResult.success()) {
            return null;
        }

        if (failure != null) {
            return new ErrorSnapshot(
                    failure.getClass().getName(),
                    failureMessage(failure),
                    stackTrace(failure));
        }

        return new ErrorSnapshot("NODE_EXECUTION_FAILED", failureMessage(nodeResult), null);
    }

    private ErrorInfo errorInfo(NodeResult nodeResult, Throwable failure) {
        if (failure != null) {
            return new ErrorInfo(
                    failure.getClass().getName(),
                    failureMessage(failure),
                    stackTrace(failure),
                    Map.of());
        }
        return new ErrorInfo("NODE_EXECUTION_FAILED", failureMessage(nodeResult), null, Map.of());
    }

    private String failureMessage(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getName() : message;
    }

    private String failureMessage(NodeResult nodeResult) {
        Object error = nodeResult.metadata() != null ? nodeResult.metadata().get("error") : null;
        if (error != null) {
            return String.valueOf(error);
        }
        return nodeResult.output() != null ? String.valueOf(nodeResult.output()) : "Node execution failed";
    }

    private String stackTrace(Throwable failure) {
        StringWriter writer = new StringWriter();
        failure.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }

    private Instant now() {
        return engineContext != null && engineContext.clock() != null
                ? engineContext.clock().instant()
                : Instant.now();
    }
}
