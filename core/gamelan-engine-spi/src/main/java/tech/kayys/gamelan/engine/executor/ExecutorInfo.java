package tech.kayys.gamelan.engine.executor;

import java.time.Duration;
import java.util.Map;

import tech.kayys.gamelan.engine.protocol.CommunicationType;

/**
 * Executor Information
 */
public record ExecutorInfo(
        String executorId,
        String executorType,
        CommunicationType communicationType,
        String endpoint,
        Duration timeout,
        Map<String, String> metadata) {
}
