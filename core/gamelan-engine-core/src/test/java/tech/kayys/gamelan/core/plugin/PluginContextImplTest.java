package tech.kayys.gamelan.core.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import tech.kayys.gamelan.engine.config.Configuration;
import tech.kayys.gamelan.engine.context.EngineContext;
import tech.kayys.gamelan.engine.plugin.PluginRuntimeInfo;
import tech.kayys.gamelan.engine.plugin.SemVer;

class PluginContextImplTest {

    @Test
    void runtimeInfoMapsDeploymentModeAndVersionFromConfiguration() {
        EngineContext engineContext = engineContext(new MapConfiguration(Map.of(
                "gamelan.deployment-mode", "SERVER",
                "gamelan.engine.version", "3.2.1")));

        PluginRuntimeInfo runtimeInfo = new PluginContextImpl(engineContext, "example").runtimeInfo();

        assertEquals("distributed", runtimeInfo.runtimeMode());
        assertEquals(new SemVer(3, 2, 1), runtimeInfo.engineVersion());
    }

    @Test
    void runtimeInfoFallsBackToStandaloneWhenConfigurationMissing() {
        EngineContext engineContext = engineContext(new MapConfiguration(Map.of(
                "gamelan.engine.version", "invalid")));

        PluginRuntimeInfo runtimeInfo = new PluginContextImpl(engineContext, "example").runtimeInfo();

        assertEquals("standalone", runtimeInfo.runtimeMode());
        assertEquals(new SemVer(0, 0, 0), runtimeInfo.engineVersion());
    }

    private static EngineContext engineContext(Configuration configuration) {
        return (EngineContext) Proxy.newProxyInstance(
                EngineContext.class.getClassLoader(),
                new Class<?>[] { EngineContext.class },
                (proxy, method, args) -> {
                    if ("configuration".equals(method.getName())) {
                        return configuration;
                    }
                    if ("attributes".equals(method.getName())) {
                        return Map.of();
                    }
                    return null;
                });
    }

    private record MapConfiguration(Map<String, String> values) implements Configuration {

        @Override
        public Optional<String> get(String key) {
            return Optional.ofNullable(values.get(key));
        }

        @Override
        public <T> Optional<T> get(String key, Class<T> type) {
            return get(key).filter(type::isInstance).map(type::cast);
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
}
