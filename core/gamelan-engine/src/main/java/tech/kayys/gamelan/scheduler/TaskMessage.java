package tech.kayys.gamelan.scheduler;

import java.util.Map;

public record TaskMessage(
        String runId,
        String nodeId,
        int attempt,
        String token,
        Map<String, Object> context,
        String targetExecutor) {
}
