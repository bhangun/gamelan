package tech.kayys.gamelan.runtime.context;

import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Encapsulates runtime environment configuration.
 * Detects standalone vs distributed mode and manages resource allocation.
 */
@ApplicationScoped
public class ExecutionEnvironment {

    private static final Logger LOG = LoggerFactory.getLogger(ExecutionEnvironment.class);

    public enum Mode {
        STANDALONE,
        DISTRIBUTED
    }

    private final Mode mode;
    private final boolean productionMode;

    public ExecutionEnvironment() {
        // Detect mode from environment or configuration
        String modeStr = System.getProperty("gamelan.runtime.mode", "standalone");
        this.mode = Mode.valueOf(modeStr.toUpperCase());
        this.productionMode = "production".equals(System.getProperty("gamelan.environment", "development"));

        LOG.info("ExecutionEnvironment initialized: mode={}, production={}", mode, productionMode);
    }

    /**
     * Get the runtime mode
     */
    public Mode getMode() {
        return mode;
    }

    /**
     * Check if running in standalone mode
     */
    public boolean isStandalone() {
        return mode == Mode.STANDALONE;
    }

    /**
     * Check if running in distributed mode
     */
    public boolean isDistributed() {
        return mode == Mode.DISTRIBUTED;
    }

    /**
     * Check if running in production mode
     */
    public boolean isProduction() {
        return productionMode;
    }

    /**
     * Check if running in development mode
     */
    public boolean isDevelopment() {
        return !productionMode;
    }
}
