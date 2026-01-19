package tech.kayys.gamelan.registry;

import java.util.Map;

/**
 * Executor registry statistics
 */
public record ExecutorStatistics(
    int totalExecutors,
    int healthyExecutors,
    int unhealthyExecutors,
    Map<String, Integer> executorsByType,
    Map<tech.kayys.gamelan.engine.protocol.CommunicationType, Integer> executorsByCommunicationType,
    long lastUpdatedTimestamp
) {
}