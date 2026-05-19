package tech.kayys.gamelan.plugin.ai;

public record LlmUsage(
        long promptTokens,
        long completionTokens,
        long totalTokens) {
}
