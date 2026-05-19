package tech.kayys.gamelan.core.execution;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.helpers.test.UniAssertSubscriber;
import tech.kayys.gamelan.engine.context.EngineContext;
import tech.kayys.gamelan.engine.executor.ExecutorDispatcher;
import tech.kayys.gamelan.engine.extension.ExtensionRegistry;
import tech.kayys.gamelan.engine.node.NodeContext;
import tech.kayys.gamelan.engine.node.NodeExecutionContext;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.node.NodeResult;
import tech.kayys.gamelan.engine.node.NodeTypeHandler;
import tech.kayys.gamelan.engine.signal.SignalHandler;
import tech.kayys.gamelan.engine.workflow.WorkflowInterceptor;
import tech.kayys.gamelan.engine.plugin.PluginRegistry;
import tech.kayys.gamelan.engine.plugin.PluginRegistry.LoadedPlugin;
import tech.kayys.gamelan.plugin.interceptor.ExecutionInterceptorPlugin;

public class DefaultWorkflowEngineTest {

    private DefaultWorkflowEngine engine;
    private EngineContext engineContext;
    private PluginRegistry pluginRegistry;
    private ExecutorDispatcher executorDispatcher;
    private List<String> executionOrder;

    @BeforeEach
    void setup() {
        engine = new DefaultWorkflowEngine();
        engineContext = Mockito.mock(EngineContext.class);
        pluginRegistry = Mockito.mock(PluginRegistry.class);
        executorDispatcher = Mockito.mock(ExecutorDispatcher.class);
        executionOrder = new ArrayList<>();

        Mockito.when(engineContext.pluginRegistry()).thenReturn(pluginRegistry);
        Mockito.when(engineContext.executorDispatcher()).thenReturn(executorDispatcher);
        engine.initialize(engineContext);
    }

    @Test
    void testExecuteNodeWithInterceptors() {
        // Arrange
        NodeContext nodeContext = new NodeContext(NodeId.of("node-1"), "test-type", Map.of(), Map.of());
        NodeExecutionContext nodeExecutionContext = Mockito.mock(NodeExecutionContext.class);

        // Mock Dispatcher
        Mockito.when(executorDispatcher.dispatch(nodeContext, nodeExecutionContext))
                .thenAnswer(inv -> {
                    executionOrder.add("EXECUTION");
                    return CompletableFuture.completedFuture(NodeResult.success(null));
                });

        // Create Mock Interceptors
        ExecutionInterceptorPlugin interceptor1 = new MockInterceptor("I1", 1, executionOrder);
        ExecutionInterceptorPlugin interceptor2 = new MockInterceptor("I2", 2, executionOrder);

        // Mock PluginRegistry
        LoadedPlugin lp1 = Mockito.mock(LoadedPlugin.class);
        Mockito.when(lp1.getPlugin()).thenReturn(interceptor1);

        LoadedPlugin lp2 = Mockito.mock(LoadedPlugin.class);
        Mockito.when(lp2.getPlugin()).thenReturn(interceptor2);

        Mockito.when(pluginRegistry.getAllPlugins()).thenReturn(java.util.Map.of(
                "p1", lp1,
                "p2", lp2));

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
    void executeNode_prefersRegisteredNodeTypeHandlerOverExecutorDispatcher() {
        NodeContext nodeContext = new NodeContext(NodeId.of("node-1"), "INLINE", Map.of(), Map.of());
        NodeExecutionContext nodeExecutionContext = Mockito.mock(NodeExecutionContext.class);
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
        Mockito.when(pluginRegistry.getAllPlugins()).thenReturn(Map.of());

        NodeResult result = engine.executeNode(nodeContext, nodeExecutionContext)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem(Duration.ofSeconds(5))
                .getItem();

        Assertions.assertEquals(handled, result);
        Assertions.assertEquals(List.of("HANDLER"), executionOrder);
        Mockito.verifyNoInteractions(executorDispatcher);
    }

    // Mock Interceptor Class
    static class MockInterceptor implements ExecutionInterceptorPlugin {
        private final String name;
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
}
