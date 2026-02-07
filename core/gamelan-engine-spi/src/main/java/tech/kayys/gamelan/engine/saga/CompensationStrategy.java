package tech.kayys.gamelan.engine.saga;

/**
 * Compensation strategy for saga pattern
 */
public enum CompensationStrategy {
    /**
     * Execute compensations sequentially in reverse order
     */
    SEQUENTIAL,

    /**
     * Execute all compensations in parallel
     */
    PARALLEL,

    /**
     * Use custom compensation logic (plugin-based)
     */
    CUSTOM
}
