package tech.kayys.gamelan.engine.plugin;

import java.util.Optional;

/**
 * Service registry for inter-plugin communication
 * 
 * Plugins can register services that other plugins can discover and use.
 */
public interface ServiceRegistry {

    /**
     * Register a service
     * 
     * @param serviceType the service interface type
     * @param service     the service implementation
     * @param <T>         the service type
     */
    <T> void registerService(Class<T> serviceType, T service);

    /**
     * Unregister a service
     * 
     * @param serviceType the service interface type
     * @param <T>         the service type
     */
    <T> void unregisterService(Class<T> serviceType);

    /**
     * Get a service
     * 
     * @param serviceType the service interface type
     * @param <T>         the service type
     * @return the service if registered
     */
    <T> Optional<T> getService(Class<T> serviceType);

    /**
     * Check if a service is registered
     * 
     * @param serviceType the service interface type
     * @return true if the service is registered
     */
    boolean hasService(Class<?> serviceType);
}
