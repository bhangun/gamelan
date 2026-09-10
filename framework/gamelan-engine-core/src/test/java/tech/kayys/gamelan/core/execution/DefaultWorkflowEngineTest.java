package tech.kayys.gamelan.core.execution;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.helpers.test.UniAssertSubscriber;
import tech.kayys.gamelan.engine.config.Configuration;
import tech.kayys.gamelan.engine.context.EngineContext;
import tech.kayys.gamelan.engine.context.SecurityContext;
import tech.kayys.gamelan.engine.context.WorkflowContext;
import tech.kayys.gamelan.engine.event.EventBus;
import tech.kayys.gamelan.engine.event.EventPublisher;
import tech.kayys.gamelan.engine.event.ExecutionEvent;
import tech.kayys.gamelan.engine.event.NodeCompletedEvent;
import tech.kayys.gamelan.engine.event.NodeFailedEvent;
import tech.kayys.gamelan.engine.event.NodeStartedEvent;
import tech.kayys.gamelan.engine.executor.ExecutorClientFactory;
import tech.kayys.gamelan.engine.executor.ExecutorDispatcher;
import tech.kayys.gamelan.engine.extension.ExtensionRegistry;
import tech.kayys.gamelan.engine.node.NodeContext;
import tech.kayys.gamelan.engine.node.NodeExecutionContext;
import tech.kayys.gamelan.engine.node.NodeExecutionSnapshot;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.node.NodeResult;
import tech.kayys.gamelan.engine.node.NodeTypeHandler;
import tech.kayys.gamelan.engine.persistence.PersistenceProvider;
import tech.kayys.gamelan.engine.run.RunStatus;
import tech.kayys.gamelan.engine.signal.SignalContext;
import tech.kayys.gamelan.engine.signal.SignalHandler;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinitionId;
import tech.kayys.gamelan.engine.workflow.WorkflowInterceptor;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;
import tech.kayys.gamelan.engine.plugin.PluginRegistry;
import tech.kayys.gamelan.engine.plugin.PluginRegistry.LoadedPlugin;
import tech.kayys.gamelan.engine.plugin.PluginMetadata;
import tech.kayys.gamelan.plugin.interceptor.ExecutionInterceptorPlugin;

public class DefaultWorkflowEngineTest {

    private DefaultWorkflowEngine engine;
    private EngineContext engineContext;
    private PluginRegistry pluginRegistry;
    private RecordingExecutorDispatcher executorDispatcher;
    private List<String> executionOrder;

    @BeforeEach
    void setup() {
        engine = new DefaultWorkflowEngine();
        pluginRegistry = new PluginRegistry();
        executorDispatcher = new RecordingExecutorDispatcher();
        engineContext = new FakeEngineContext(pluginRegistry, executorDispatcher, null);
        executionOrder = new ArrayList<>();
        engine.initialize(engineContext);
    }

    @Test
    void testExecuteNodeWithInterceptors() {
        // Arrange
        NodeContext nodeContext = new NodeContext(NodeId.of("node-1"), "test-type", Map.of(), Map.of());
        NodeExecutionContext nodeExecutionContext = new FakeNodeExecutionContext(engineContext);

        executorDispatcher.onDispatch = () -> executionOrder.add("EXECUTION");
        executorDispatcher.result = NodeResult.success(null);

        // Create Mock Interceptors
        ExecutionInterceptorPlugin interceptor1 = new MockInterceptor("I1", 1, executionOrder);
        ExecutionInterceptorPlugin interceptor2 = new MockInterceptor("I2", 2, executionOrder);

        pluginRegistry.register(new LoadedPlugin(interceptor1, metadata("p1"), null));
        pluginRegistry.register(new LoadedPlugin(interceptor2, metadata("p2"), null));

        // Act
        Uni<NodeResult> resultUni = engine.executeNode(nodeContext, nodeExecutionContext);

        // Assert
        NodeResult result = resultUni.subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem(Duration.ofSeconds(5))
                .getItem();

        Assertions.assertTrue(result.success());

        // Check Order:
        // Before I1 (order 1) -> Before I2 (order 2) -> Execution -> After I2 -> After
        // I1
        List<String> expected = List.of(
                "I1:before",
                "I2:before",
                "EXECUTION",
                "I2:after",
                "I1:after");

        Assertions.assertEquals(expected, executionOrder);
    }

    @Test
    void executeNode_defersHooksAndDispatchUntilSubscription() {
        NodeContext nodeContext = new NodeContext(NodeId.of("node-1"), "test-type", Map.of(), Map.of());
        NodeExecutionContext nodeExecutionContext = new FakeNodeExecutionContext(engineContext);
        executorDispatcher.onDispatch = () -> executionOrder.add("EXECUTION");
        executorDispatcher.result = NodeResult.success(null);

        pluginRegistry.register(new LoadedPlugin(new MockInterceptor("I1", 1, executionOrder), metadata("p1"), null));

        Uni<NodeResult> resultUni = engine.executeNode(nodeContext, nodeExecutionContext);

        Assertions.assertEquals(List.of(), executionOrder);
        Assertions.assertEquals(0, executorDispatcher.dispatchCount);

        NodeResult result = resultUni.subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem(Duration.ofSeconds(5))
                .getItem();

        Assertions.assertTrue(result.success());
        Assertions.assertEquals(List.of("I1:before", "EXECUTION", "I1:after"), executionOrder);
        Assertions.assertEquals(1, executorDispatcher.dispatchCount);
    }

    @Test
    void executeNode_continuesWhenBeforeInterceptorFailsByDefault() {
        NodeContext nodeContext = new NodeContext(NodeId.of("node-1"), "test-type", Map.of(), Map.of());
        NodeExecutionContext nodeExecutionContext = new FakeNodeExecutionContext(engineContext);
        executorDispatcher.onDispatch = () -> executionOrder.add("EXECUTION");
        executorDispatcher.result = NodeResult.success(null);

        pluginRegistry.register(new LoadedPlugin(new FailingBeforeInterceptor("I1", executionOrder), metadata("p1"), null));

        NodeResult result = engine.executeNode(nodeContext, nodeExecutionContext)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem(Duration.ofSeconds(5))
                .getItem();

        Assertions.assertTrue(result.success());
        Assertions.assertEquals(List.of("I1:before", "EXECUTION", "I1:after"), executionOrder);
        Assertions.assertEquals(1, executorDispatcher.dispatchCount);
    }

    @Test
    void executeNode_failsBeforeDispatchWhenConfigured() {
        engineContext = new FakeEngineContext(pluginRegistry, executorDispatcher, null, new MapConfiguration(Map.of(
                "gamelan.engine.execution.interceptors.before.fail-on-error", "true")));
        engine.initialize(engineContext);

        NodeContext nodeContext = new NodeContext(NodeId.of("node-1"), "test-type", Map.of(), Map.of());
        NodeExecutionContext nodeExecutionContext = new FakeNodeExecutionContext(engineContext);
        executorDispatcher.onDispatch = () -> executionOrder.add("EXECUTION");
        executorDispatcher.result = NodeResult.success(null);

        pluginRegistry.register(new LoadedPlugin(new FailingBeforeInterceptor("I1", executionOrder), metadata("p1"), null));

        NodeResult result = engine.executeNode(nodeContext, nodeExecutionContext)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem(Duration.ofSeconds(5))
                .getItem();

        Assertions.assertFalse(result.success());
        Assertions.assertEquals("I1 denied before execution", result.metadata().get("error"));
        Assertions.assertEquals(List.of("I1:before", "I1:error"), executionOrder);
        Assertions.assertEquals(0, executorDispatcher.dispatchCount);
    }

    @Test
    void executeNode_publishesStartedAndCompletedEvents() {
        RecordingEventPublisher eventPublisher = new RecordingEventPublisher();
        engineContext = new FakeEngineContext(pluginRegistry, executorDispatcher, null, new MapConfiguration(Map.of()),
                eventPublisher);
        engine.initialize(engineContext);

        WorkflowRunId runId = WorkflowRunId.of("run-1");
        NodeContext nodeContext = new NodeContext(NodeId.of("node-1"), "test-type", Map.of(),
                Map.of("attempt", 2));
        NodeExecutionContext nodeExecutionContext = new FakeNodeExecutionContext(engineContext, workflow(runId));
        executorDispatcher.result = NodeResult.success(Map.of("answer", 42));

        NodeResult result = engine.executeNode(nodeContext, nodeExecutionContext)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem(Duration.ofSeconds(5))
                .getItem();

        Assertions.assertTrue(result.success());
        Assertions.assertEquals(2, eventPublisher.events.size());
        Assertions.assertInstanceOf(NodeStartedEvent.class, eventPublisher.events.get(0));
        Assertions.assertInstanceOf(NodeCompletedEvent.class, eventPublisher.events.get(1));

        NodeStartedEvent started = (NodeStartedEvent) eventPublisher.events.get(0);
        NodeCompletedEvent completed = (NodeCompletedEvent) eventPublisher.events.get(1);
        Assertions.assertEquals(runId, started.runId());
        Assertions.assertEquals(nodeContext.nodeId(), started.nodeId());
        Assertions.assertEquals(2, started.attempt());
        Assertions.assertEquals(runId, completed.runId());
        Assertions.assertEquals(nodeContext.nodeId(), completed.nodeId());
        Assertions.assertEquals(2, completed.attempt());
        Assertions.assertEquals(Map.of("answer", 42), completed.output());
    }

    @Test
    void executeNode_publishesStartedAndFailedEventsWhenDispatchFails() {
        RecordingEventPublisher eventPublisher = new RecordingEventPublisher();
        engineContext = new FakeEngineContext(pluginRegistry, executorDispatcher, null, new MapConfiguration(Map.of()),
                eventPublisher);
        engine.initialize(engineContext);

        WorkflowRunId runId = WorkflowRunId.of("run-1");
        NodeContext nodeContext = new NodeContext(NodeId.of("node-1"), "test-type", Map.of(), Map.of());
        NodeExecutionContext nodeExecutionContext = new FakeNodeExecutionContext(engineContext, workflow(runId));
        executorDispatcher.failure = new IllegalStateException("executor offline");

        NodeResult result = engine.executeNode(nodeContext, nodeExecutionContext)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem(Duration.ofSeconds(5))
                .getItem();

        Assertions.assertFalse(result.success());
        Assertions.assertEquals(2, eventPublisher.events.size());
        Assertions.assertInstanceOf(NodeStartedEvent.class, eventPublisher.events.get(0));
        Assertions.assertInstanceOf(NodeFailedEvent.class, eventPublisher.events.get(1));

        NodeFailedEvent failed = (NodeFailedEvent) eventPublisher.events.get(1);
        Assertions.assertEquals(runId, failed.runId());
        Assertions.assertEquals(nodeContext.nodeId(), failed.nodeId());
        Assertions.assertEquals("java.lang.IllegalStateException", failed.error().code());
        Assertions.assertEquals("executor offline", failed.error().message());
        Assertions.assertFalse(failed.willRetry());
    }

    @Test
    void executeNode_prefersRegisteredNodeTypeHandlerOverExecutorDispatcher() {
        NodeContext nodeContext = new NodeContext(NodeId.of("node-1"), "INLINE", Map.of(), Map.of());
        NodeExecutionContext nodeExecutionContext = new FakeNodeExecutionContext(engineContext);
        NodeResult handled = NodeResult.success(Map.of("handled", true));

        engine.extensionRegistry = new SingleHandlerRegistry(new NodeTypeHandler() {
            @Override
            public String nodeType() {
                return "INLINE";
            }

            @Override
            public NodeResult execute(NodeExecutionContext ctx) {
                executionOrder.add("HANDLER");
                return handled;
            }
        });

        NodeResult result = engine.executeNode(nodeContext, nodeExecutionContext)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem(Duration.ofSeconds(5))
                .getItem();

        Assertions.assertEquals(handled, result);
        Assertions.assertEquals(List.of("HANDLER"), executionOrder);
        Assertions.assertEquals(0, executorDispatcher.dispatchCount);
    }

    @Test
    void executeNode_persistsNodeSnapshotAndOutputVariables() {
        RecordingPersistenceProvider persistence = new RecordingPersistenceProvider();
        engineContext = new FakeEngineContext(pluginRegistry, executorDispatcher, persistence);
        engine.initialize(engineContext);

        WorkflowRunId runId = WorkflowRunId.of("run-1");
        NodeContext nodeContext = new NodeContext(NodeId.of("node-1"), "test-type", Map.of(),
                Map.of("attempt", 2));
        NodeExecutionContext nodeExecutionContext = new FakeNodeExecutionContext(engineContext, workflow(runId));
        executorDispatcher.result = NodeResult.success(Map.of("answer", 42, "status", "ok"));

        NodeResult result = engine.executeNode(nodeContext, nodeExecutionContext)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem(Duration.ofSeconds(5))
                .getItem();

        Assertions.assertTrue(result.success());
        Assertions.assertEquals(runId, persistence.updatedRunId);
        Assertions.assertEquals(nodeContext.nodeId(), persistence.updatedNodeId);
        Assertions.assertEquals("COMPLETED", persistence.snapshot.status());
        Assertions.assertEquals(2, persistence.snapshot.attempt());
        Assertions.assertEquals(Map.of("answer", 42, "status", "ok"), persistence.snapshot.output());
        Assertions.assertEquals(Map.of("answer", 42, "status", "ok"), persistence.contextVariables);
    }

    @Test
    void executeNode_returnsFailureWhenPersistenceUpdateFails() {
        RecordingPersistenceProvider persistence = new RecordingPersistenceProvider();
        persistence.failNodeUpdate = true;
        engineContext = new FakeEngineContext(pluginRegistry, executorDispatcher, persistence);
        engine.initialize(engineContext);

        NodeContext nodeContext = new NodeContext(NodeId.of("node-1"), "test-type", Map.of(), Map.of());
        NodeExecutionContext nodeExecutionContext = new FakeNodeExecutionContext(engineContext,
                workflow(WorkflowRunId.of("run-1")));
        executorDispatcher.result = NodeResult.success(Map.of("answer", 42));

        NodeResult result = engine.executeNode(nodeContext, nodeExecutionContext)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem(Duration.ofSeconds(5))
                .getItem();

        Assertions.assertFalse(result.success());
        Assertions.assertEquals("persistence down", result.metadata().get("error"));
        Assertions.assertEquals(1, executorDispatcher.dispatchCount);
        Assertions.assertEquals(Map.of(), persistence.contextVariables);
    }

    @Test
    void executeNode_persistsFailureSnapshotWhenDispatcherFails() {
        RecordingPersistenceProvider persistence = new RecordingPersistenceProvider();
        engineContext = new FakeEngineContext(pluginRegistry, executorDispatcher, persistence);
        engine.initialize(engineContext);

        WorkflowRunId runId = WorkflowRunId.of("run-1");
        NodeContext nodeContext = new NodeContext(NodeId.of("node-1"), "test-type", Map.of(),
                Map.of("attempt", 3));
        NodeExecutionContext nodeExecutionContext = new FakeNodeExecutionContext(engineContext, workflow(runId));
        executorDispatcher.failure = new IllegalStateException("executor offline");

        NodeResult result = engine.executeNode(nodeContext, nodeExecutionContext)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem(Duration.ofSeconds(5))
                .getItem();

        Assertions.assertFalse(result.success());
        Assertions.assertEquals("executor offline", result.metadata().get("error"));
        Assertions.assertEquals(runId, persistence.updatedRunId);
        Assertions.assertEquals(nodeContext.nodeId(), persistence.updatedNodeId);
        Assertions.assertEquals("FAILED", persistence.snapshot.status());
        Assertions.assertEquals(3, persistence.snapshot.attempt());
        Assertions.assertEquals("java.lang.IllegalStateException", persistence.snapshot.error().code());
        Assertions.assertEquals("executor offline", persistence.snapshot.error().message());
        Assertions.assertTrue(persistence.snapshot.error().stackTrace().contains("executor offline"));
        Assertions.assertEquals(Map.of(), persistence.contextVariables);
    }

    @Test
    void executeNode_invokesExecutionInterceptorOnErrorInReverseOrder() {
        NodeContext nodeContext = new NodeContext(NodeId.of("node-1"), "test-type", Map.of(), Map.of());
        NodeExecutionContext nodeExecutionContext = new FakeNodeExecutionContext(engineContext);
        executorDispatcher.onDispatch = () -> executionOrder.add("EXECUTION");
        executorDispatcher.failure = new IllegalStateException("executor offline");

        pluginRegistry.register(new LoadedPlugin(new MockInterceptor("I1", 1, executionOrder), metadata("p1"), null));
        pluginRegistry.register(new LoadedPlugin(new MockInterceptor("I2", 2, executionOrder), metadata("p2"), null));

        NodeResult result = engine.executeNode(nodeContext, nodeExecutionContext)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem(Duration.ofSeconds(5))
                .getItem();

        Assertions.assertFalse(result.success());
        Assertions.assertEquals(List.of(
                "I1:before",
                "I2:before",
                "EXECUTION",
                "I2:error",
                "I1:error"),
                executionOrder);
    }

    @Test
    void executeNode_persistsFailureSnapshotWhenExecutorReturnsFailedResult() {
        RecordingPersistenceProvider persistence = new RecordingPersistenceProvider();
        engineContext = new FakeEngineContext(pluginRegistry, executorDispatcher, persistence);
        engine.initialize(engineContext);

        WorkflowRunId runId = WorkflowRunId.of("run-1");
        NodeContext nodeContext = new NodeContext(NodeId.of("node-1"), "test-type", Map.of(), Map.of());
        NodeExecutionContext nodeExecutionContext = new FakeNodeExecutionContext(engineContext, workflow(runId));
        executorDispatcher.result = NodeResult.failure("business rule rejected");

        NodeResult result = engine.executeNode(nodeContext, nodeExecutionContext)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem(Duration.ofSeconds(5))
                .getItem();

        Assertions.assertFalse(result.success());
        Assertions.assertEquals("FAILED", persistence.snapshot.status());
        Assertions.assertEquals("NODE_EXECUTION_FAILED", persistence.snapshot.error().code());
        Assertions.assertEquals("business rule rejected", persistence.snapshot.error().message());
    }

    @Test
    void executeNode_doesNotPromoteFailedOutputToWorkflowVariables() {
        RecordingPersistenceProvider persistence = new RecordingPersistenceProvider();
        engineContext = new FakeEngineContext(pluginRegistry, executorDispatcher, persistence);
        engine.initialize(engineContext);

        WorkflowRunId runId = WorkflowRunId.of("run-1");
        NodeContext nodeContext = new NodeContext(NodeId.of("node-1"), "test-type", Map.of(), Map.of());
        NodeExecutionContext nodeExecutionContext = new FakeNodeExecutionContext(engineContext, workflow(runId));
        executorDispatcher.result = new NodeResult(
                false,
                Map.of("partial", "unsafe"),
                Map.of("error", "business rule rejected"),
                Instant.now());

        NodeResult result = engine.executeNode(nodeContext, nodeExecutionContext)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem(Duration.ofSeconds(5))
                .getItem();

        Assertions.assertFalse(result.success());
        Assertions.assertEquals(Map.of("partial", "unsafe"), persistence.snapshot.output());
        Assertions.assertEquals("business rule rejected", persistence.snapshot.error().message());
        Assertions.assertEquals(Map.of(), persistence.contextVariables);
    }

    private static PluginMetadata metadata(String id) {
        return new PluginMetadata(id, id, "1.0.0", "test", "test plugin", List.of(), Map.of());
    }

    // Mock Interceptor Class
    static class MockInterceptor implements ExecutionInterceptorPlugin {
        protected final String name;
        private final int order;
        private final List<String> log;

        public MockInterceptor(String name, int order, List<String> log) {
            this.name = name;
            this.order = order;
            this.log = log;
        }

        @Override
        public int getOrder() {
            return order;
        }

        @Override
        public Uni<Void> beforeExecution(TaskContext task) {
            log.add(name + ":before");
            return Uni.createFrom().voidItem();
        }

        @Override
        public Uni<Void> afterExecution(TaskContext task, ExecutionResult result) {
            log.add(name + ":after");
            return Uni.createFrom().voidItem();
        }

        @Override
        public Uni<Void> onError(TaskContext task, Throwable error) {
            log.add(name + ":error");
            return Uni.createFrom().voidItem();
        }

        @Override
        public void initialize(tech.kayys.gamelan.engine.plugin.PluginContext context) {
        }

        @Override
        public void start() {
        }

        @Override
        public void stop() {
        }

        @Override
        public tech.kayys.gamelan.engine.plugin.PluginMetadata getMetadata() {
            return null;
        }
    }

    static class FailingBeforeInterceptor extends MockInterceptor {
        FailingBeforeInterceptor(String name, List<String> log) {
            super(name, 1, log);
        }

        @Override
        public Uni<Void> beforeExecution(TaskContext task) {
            super.beforeExecution(task);
            return Uni.createFrom().failure(new IllegalStateException(name + " denied before execution"));
        }
    }

    static class SingleHandlerRegistry implements ExtensionRegistry {
        private final NodeTypeHandler handler;

        SingleHandlerRegistry(NodeTypeHandler handler) {
            this.handler = handler;
        }

        @Override
        public void registerInterceptor(WorkflowInterceptor interceptor) {
        }

        @Override
        public void registerNodeType(NodeTypeHandler handler) {
        }

        @Override
        public void registerSignalHandler(SignalHandler handler) {
        }

        @Override
        public Collection<WorkflowInterceptor> interceptors() {
            return List.of();
        }

        @Override
        public NodeTypeHandler nodeType(String type) {
            return handler.nodeType().equals(type) ? handler : null;
        }

        @Override
        public Collection<SignalHandler> signalHandlers(String signalType) {
            return List.of();
        }
    }

    static class RecordingExecutorDispatcher implements ExecutorDispatcher {
        Runnable onDispatch = () -> {
        };
        NodeResult result = NodeResult.success(null);
        Throwable failure;
        int dispatchCount;

        @Override
        public CompletionStage<NodeResult> dispatch(NodeContext nodeContext, NodeExecutionContext executionContext) {
            dispatchCount++;
            onDispatch.run();
            if (failure != null) {
                return CompletableFuture.failedFuture(failure);
            }
            return CompletableFuture.completedFuture(result);
        }
    }

    record FakeEngineContext(
            PluginRegistry pluginRegistry,
            ExecutorDispatcher executorDispatcher,
            PersistenceProvider persistence,
            Configuration configuration,
            EventPublisher eventPublisher) implements EngineContext {
        FakeEngineContext(
                PluginRegistry pluginRegistry,
                ExecutorDispatcher executorDispatcher,
                PersistenceProvider persistence) {
            this(pluginRegistry, executorDispatcher, persistence, new MapConfiguration(Map.of()));
        }

        FakeEngineContext(
                PluginRegistry pluginRegistry,
                ExecutorDispatcher executorDispatcher,
                PersistenceProvider persistence,
                Configuration configuration) {
            this(pluginRegistry, executorDispatcher, persistence, configuration, null);
        }

        @Override
        public Clock clock() {
            return Clock.systemUTC();
        }

        @Override
        public EventBus eventBus() {
            return null;
        }

        @Override
        public PersistenceProvider persistence() {
            return persistence;
        }

        @Override
        public SecurityContext security() {
            return null;
        }

        @Override
        public <T> T getService(Class<T> type) {
            return null;
        }

        @Override
        public Map<String, Object> attributes() {
            return Map.of();
        }

        @Override
        public EventPublisher eventPublisher() {
            return eventPublisher;
        }

        @Override
        public Configuration configuration() {
            return configuration;
        }

        @Override
        public ExecutorClientFactory executorClientFactory() {
            return null;
        }
    }

    private record MapConfiguration(Map<String, String> values) implements Configuration {
        @Override
        public Optional<String> get(String key) {
            return Optional.ofNullable(values.get(key));
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> Optional<T> get(String key, Class<T> type) {
            return get(key).map(value -> {
                if (type == String.class) {
                    return (T) value;
                }
                if (type == Boolean.class) {
                    return (T) Boolean.valueOf(value);
                }
                if (type == Integer.class) {
                    return (T) Integer.valueOf(value);
                }
                if (type == Long.class) {
                    return (T) Long.valueOf(value);
                }
                throw new IllegalArgumentException("Unsupported config type: " + type);
            });
        }

        @Override
        public String require(String key) {
            return get(key).orElseThrow();
        }

        @Override
        public Configuration scoped(String prefix) {
            return this;
        }
    }

    static class RecordingEventPublisher implements EventPublisher {
        final List<ExecutionEvent> events = new ArrayList<>();

        @Override
        public void publish(String eventType, Object payload, WorkflowContext workflowContext) {
        }

        @Override
        public void publishSystem(String eventType, Object payload) {
        }

        @Override
        public Uni<Void> publish(List<ExecutionEvent> events) {
            this.events.addAll(events);
            return Uni.createFrom().voidItem();
        }

        @Override
        public Uni<Void> publishRetry(WorkflowRunId runId, NodeId nodeId) {
            return Uni.createFrom().voidItem();
        }
    }

    record FakeNodeExecutionContext(EngineContext engine, WorkflowContext workflow) implements NodeExecutionContext {
        FakeNodeExecutionContext(EngineContext engine) {
            this(engine, null);
        }

        @Override
        public WorkflowContext workflow() {
            return workflow;
        }

        @Override
        public void emitEvent(String type, Object payload) {
        }

        @Override
        public void setVariable(String key, Object value) {
        }

        @Override
        public void suspend(String reason) {
        }
    }

    private static WorkflowContext workflow(WorkflowRunId runId) {
        return new FakeWorkflowContext(runId, new HashMap<>());
    }

    record FakeWorkflowContext(
            WorkflowRunId runId,
            Map<String, Object> variables) implements WorkflowContext {
        @Override
        public WorkflowDefinitionId definitionId() {
            return WorkflowDefinitionId.of("definition-1");
        }

        @Override
        public TenantId tenantId() {
            return TenantId.of("tenant-1");
        }

        @Override
        public RunStatus status() {
            return RunStatus.RUNNING;
        }

        @Override
        public Instant startedAt() {
            return Instant.EPOCH;
        }

        @Override
        public Instant updatedAt() {
            return Instant.EPOCH;
        }

        @Override
        public Map<NodeId, NodeResult> completedNodes() {
            return Map.of();
        }
    }

    static class RecordingPersistenceProvider implements PersistenceProvider {
        final Map<String, Object> contextVariables = new HashMap<>();
        WorkflowRunId updatedRunId;
        NodeId updatedNodeId;
        NodeExecutionSnapshot snapshot;
        boolean failNodeUpdate;

        @Override
        public void saveWorkflow(WorkflowContext workflow) {
        }

        @Override
        public Optional<WorkflowContext> loadWorkflow(WorkflowRunId runId) {
            return Optional.empty();
        }

        @Override
        public void appendEvent(WorkflowRunId runId, String eventType, Object payload) {
        }

        @Override
        public void saveNodeResult(WorkflowRunId runId, NodeId nodeId, NodeResult result) {
        }

        @Override
        public void saveSignal(WorkflowRunId runId, SignalContext signal) {
        }

        @Override
        public void updateContextVariable(WorkflowRunId runId, String key, Object value) {
            contextVariables.put(key, value);
        }

        @Override
        public void updateNodeExecution(WorkflowRunId runId, NodeId nodeId, NodeExecutionSnapshot snapshot) {
            if (failNodeUpdate) {
                throw new IllegalStateException("persistence down");
            }
            this.updatedRunId = runId;
            this.updatedNodeId = nodeId;
            this.snapshot = snapshot;
        }
    }
}
