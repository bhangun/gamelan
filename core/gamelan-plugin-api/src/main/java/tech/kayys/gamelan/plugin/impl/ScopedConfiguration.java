package tech.kayys.gamelan.plugin.impl;

import java.util.Optional;

import tech.kayys.gamelan.engine.config.Configuration;

/**
 * Configuration wrapper that scopes keys to a prefix
 */
public class ScopedConfiguration implements Configuration {

    private final String prefix;
    private final Configuration delegate;

    public ScopedConfiguration(String prefix, Configuration delegate) {
        this.prefix = prefix.endsWith(".") ? prefix : prefix + ".";
        this.delegate = delegate;
    }

    @Override
    public Optional<String> get(String key) {
        return delegate.get(prefix + key);
    }

    @Override
    public <T> Optional<T> get(String key, Class<T> type) {
        return delegate.get(prefix + key, type);
    }

    @Override
    public String require(String key) {
        return delegate.require(prefix + key);
    }

    @Override
    public Configuration scoped(String subPrefix) {
        return new ScopedConfiguration(prefix + subPrefix, delegate);
    }
}
