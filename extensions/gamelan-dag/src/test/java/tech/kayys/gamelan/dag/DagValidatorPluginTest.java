package tech.kayys.gamelan.dag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import tech.kayys.gamelan.engine.plugin.PluginContext;
import tech.kayys.gamelan.engine.plugin.PluginMetadata;
import tech.kayys.gamelan.engine.plugin.ServiceRegistry;
import tech.kayys.gamelan.plugin.validator.WorkflowValidatorPlugin;

class DagValidatorPluginTest {

    @Test
    void detectsCycle() {
        DagValidatorPlugin plugin = new DagValidatorPlugin();
        WorkflowValidatorPlugin.WorkflowDefinitionInfo def = new TestDefinition(
                List.of(
                        new TestNode("A", "task"),
                        new TestNode("B", "task")),
                List.of(
                        new TestTransition("A", "B"),
                        new TestTransition("B", "A")));

        List<WorkflowValidatorPlugin.ValidationError> errors = plugin.validate(def);
        assertFalse(errors.isEmpty());
        assertEquals("dag.cycle", errors.get(0).rule());
    }

    @Test
    void detectsOrphan() {
        DagValidatorPlugin plugin = new DagValidatorPlugin();
        WorkflowValidatorPlugin.WorkflowDefinitionInfo def = new TestDefinition(
                List.of(
                        new TestNode("A", "task"),
                        new TestNode("B", "task")),
                List.of());

        List<WorkflowValidatorPlugin.ValidationError> errors = plugin.validate(def);
        assertFalse(errors.isEmpty());
    }

    @Test
    void registersSchedulerServiceOnInitialize() throws Exception {
        DagValidatorPlugin plugin = new DagValidatorPlugin();
        TestServiceRegistry registry = new TestServiceRegistry();
        PluginContext context = new TestPluginContext(registry);

        plugin.initialize(context);

        Optional<DagSchedulerService> service = registry.getService(DagSchedulerService.class);
        assertTrue(service.isPresent());
        assertNotNull(service.get());

        plugin.stop();
        assertTrue(registry.getService(DagSchedulerService.class).isEmpty());
    }

    private static class TestDefinition implements WorkflowValidatorPlugin.WorkflowDefinitionInfo {
        private final List<WorkflowValidatorPlugin.NodeDefinitionInfo> nodes;
        private final List<WorkflowValidatorPlugin.TransitionInfo> transitions;

        TestDefinition(List<WorkflowValidatorPlugin.NodeDefinitionInfo> nodes, List<WorkflowValidatorPlugin.TransitionInfo> transitions) {
            this.nodes = nodes;
            this.transitions = transitions;
        }

        @Override
        public String definitionId() {
            return "test";
        }

        @Override
        public String name() {
            return "test";
        }

        @Override
        public String version() {
            return "1";
        }

        @Override
        public List<WorkflowValidatorPlugin.NodeDefinitionInfo> nodes() {
            return nodes;
        }

        @Override
        public List<WorkflowValidatorPlugin.TransitionInfo> transitions() {
            return transitions;
        }
    }

    private static class TestNode implements WorkflowValidatorPlugin.NodeDefinitionInfo {
        private final String id;
        private final String type;

        TestNode(String id, String type) {
            this.id = id;
            this.type = type;
        }

        @Override
        public String nodeId() {
            return id;
        }

        @Override
        public String nodeType() {
            return type;
        }

        @Override
        public Map<String, Object> configuration() {
            return Map.of();
        }
    }

    private static class TestTransition implements WorkflowValidatorPlugin.TransitionInfo {
        private final String from;
        private final String to;

        TestTransition(String from, String to) {
            this.from = from;
            this.to = to;
        }

        @Override
        public String fromNodeId() {
            return from;
        }

        @Override
        public String toNodeId() {
            return to;
        }

        @Override
        public String condition() {
            return null;
        }
    }

    private static class TestPluginContext implements PluginContext {
        private final ServiceRegistry serviceRegistry;

        TestPluginContext(ServiceRegistry serviceRegistry) {
            this.serviceRegistry = serviceRegistry;
        }

        @Override
        public PluginMetadata getMetadata() {
            return null;
        }

        @Override
        public org.slf4j.Logger getLogger() {
            return null;
        }

        @Override
        public Optional<String> getProperty(String key) {
            return Optional.empty();
        }

        @Override
        public String getProperty(String key, String defaultValue) {
            return defaultValue;
        }

        @Override
        public Map<String, String> getAllProperties() {
            return Map.of();
        }

        @Override
        public ServiceRegistry getServiceRegistry() {
            return serviceRegistry;
        }

        @Override
        public tech.kayys.gamelan.engine.event.EventBus getEventBus() {
            return null;
        }

        @Override
        public String getDataDirectory() {
            return null;
        }

        @Override
        public tech.kayys.gamelan.engine.plugin.PluginRuntimeInfo runtimeInfo() {
            return null;
        }

        @Override
        public tech.kayys.gamelan.engine.config.Configuration config() {
            return null;
        }

        @Override
        public tech.kayys.gamelan.engine.event.EventPublisher eventPublisher() {
            return null;
        }

        @Override
        public tech.kayys.gamelan.engine.extension.ExtensionRegistry extensions() {
            return null;
        }
    }

    private static class TestServiceRegistry implements ServiceRegistry {
        private final Map<Class<?>, Object> services = new java.util.HashMap<>();

        @Override
        public <T> void registerService(Class<T> serviceType, T service) {
            services.put(serviceType, service);
        }

        @Override
        public <T> void unregisterService(Class<T> serviceType) {
            services.remove(serviceType);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> Optional<T> getService(Class<T> serviceType) {
            return Optional.ofNullable((T) services.get(serviceType));
        }

        @Override
        public boolean hasService(Class<?> serviceType) {
            return services.containsKey(serviceType);
        }
    }
}
