package tech.kayys.silat.sdk.executor;

import java.util.List;

import tech.kayys.silat.model.CommunicationType;

/**
 * Executor configuration
 */
record ExecutorConfig(
                int maxConcurrentTasks,
                List<String> supportedNodeTypes,
                CommunicationType communicationType,
                SecurityConfig securityConfig) {
}