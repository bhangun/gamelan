package tech.kayys.gamelan.engine.plugin.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import tech.kayys.gamelan.engine.plugin.PluginMetadata;
import tech.kayys.gamelan.engine.plugin.PluginRuntimeInfo;
import tech.kayys.gamelan.engine.plugin.SemVer;

class DefaultPluginContextTest {

    @Test
    void runtimeInfoUsesExplicitRuntimeModeAndEngineVersion() {
        DefaultPluginContext context = new DefaultPluginContext(
                new PluginMetadata("test", "Test Plugin", "1.2.3", "author", "desc", null, null),
                LoggerFactory.getLogger(DefaultPluginContextTest.class),
                Map.of(
                        "gamelan.runtime.mode", "executor",
                        "gamelan.engine.version", "1.4.2"),
                null,
                null,
                null,
                null,
                "/tmp/plugin");

        PluginRuntimeInfo runtimeInfo = context.runtimeInfo();

        assertEquals("executor", runtimeInfo.runtimeMode());
        assertEquals(new SemVer(1, 4, 2), runtimeInfo.engineVersion());
    }

    @Test
    void runtimeInfoMapsDeploymentModeWhenRuntimeModeMissing() {
        DefaultPluginContext context = new DefaultPluginContext(
                new PluginMetadata("test", "Test Plugin", "1.2.3", "author", "desc", null, null),
                LoggerFactory.getLogger(DefaultPluginContextTest.class),
                Map.of(
                        "gamelan.deployment-mode", "enterprise",
                        "gamelan.engine.version", "2.0.1-SNAPSHOT"),
                null,
                null,
                null,
                null,
                "/tmp/plugin");

        PluginRuntimeInfo runtimeInfo = context.runtimeInfo();

        assertEquals("distributed", runtimeInfo.runtimeMode());
        assertEquals(new SemVer(2, 0, 1), runtimeInfo.engineVersion());
    }

    @Test
    void runtimeInfoFallsBackWhenConfigurationMissingOrInvalid() {
        DefaultPluginContext context = new DefaultPluginContext(
                new PluginMetadata("test", "Test Plugin", "1.2.3", "author", "desc", null, null),
                LoggerFactory.getLogger(DefaultPluginContextTest.class),
                Map.of("gamelan.engine.version", "not-a-semver"),
                null,
                null,
                null,
                null,
                "/tmp/plugin");

        PluginRuntimeInfo runtimeInfo = context.runtimeInfo();

        assertEquals("standalone", runtimeInfo.runtimeMode());
        assertEquals(new SemVer(0, 0, 0), runtimeInfo.engineVersion());
    }
}
