package tech.kayys.gamelan.plugin.impl;

import org.junit.jupiter.api.Test;

import tech.kayys.gamelan.engine.config.Configuration;

import tech.kayys.gamelan.plugin.event.GenericPluginEvent;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

public class PluginImplTest {

    @Test
    public void testScopedConfiguration() {
        Configuration delegate = new Configuration() {
            @Override
            public Optional<String> get(String key) {
                if ("foo.bar".equals(key))
                    return Optional.of("baz");
                return Optional.empty();
            }

            @Override
            public <T> Optional<T> get(String key, Class<T> type) {
                return Optional.empty();
            }

            @Override
            public String require(String key) {
                return get(key).orElseThrow();
            }

            @Override
            public Configuration scoped(String prefix) {
                return new ScopedConfiguration(prefix, this);
            }
        };

        Configuration scoped = new ScopedConfiguration("foo", delegate);
        assertEquals("baz", scoped.get("bar").orElse(null));
    }

    @Test
    public void testGenericPluginEvent() {
        GenericPluginEvent event = new GenericPluginEvent("source", "type", "payload", Map.of("k", "v"));
        assertEquals("source", event.getSourcePluginId());
        assertEquals("type", event.getType());
        assertEquals("payload", event.getPayload());
        assertEquals("v", event.getMetadata().get("k"));
    }
}
