package tech.kayys.gamelan.dag;

/**
 * Marker policy for DAG execution behavior.
 * Future expansion: per-node retries, batch execution rules, etc.
 */
public class DagExecutionPolicy {
    private final int maxAttempts;

    public DagExecutionPolicy(int maxAttempts) {
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    public int maxAttempts() {
        return maxAttempts;
    }
}
