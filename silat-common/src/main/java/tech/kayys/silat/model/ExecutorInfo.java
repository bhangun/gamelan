package tech.kayys.silat.model;

import java.time.Duration;
import java.util.Map;

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
