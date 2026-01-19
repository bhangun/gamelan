package tech.kayys.gamelan.sdk.executor;

import java.util.List;

import tech.kayys.gamelan.engine.protocol.CommunicationType;

/**
 * Executor configuration
 */
record ExecutorConfig(
        int maxConcurrentTasks,
        List<String> supportedNodeTypes,
        CommunicationType communicationType,
        SecurityConfig securityConfig) {
}