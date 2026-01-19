package tech.kayys.gamelan.runtime;

/**
 * @deprecated This class has been moved to tech.kayys.gamelan.core.persistence.DbInitializer
 * in gamelan-engine-core module to avoid circular dependencies between runtimes.
 * This class will be removed in a future version.
 */
@Deprecated(forRemoval = true, since = "1.0.0")
public class DbInitializer extends tech.kayys.gamelan.core.persistence.DbInitializer {
    // Delegating to parent class for backward compatibility
}
