package tech.kayys.gamelan.plugin.impl;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.enterprise.context.ApplicationScoped;
import tech.kayys.gamelan.engine.plugin.ServiceRegistry;

/**
 * Default implementation of ServiceRegistry
 */
@ApplicationScoped
public class DefaultServiceRegistry implements ServiceRegistry {

    private final Map<Class<?>, Object> services = new ConcurrentHashMap<>();

    @Override
    public <T> void registerService(Class<T> serviceType, T service) {
        if (serviceType == null) {
            throw new IllegalArgumentException("Service type cannot be null");
        }
        if (service == null) {
            throw new IllegalArgumentException("Service cannot be null");
        }
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
