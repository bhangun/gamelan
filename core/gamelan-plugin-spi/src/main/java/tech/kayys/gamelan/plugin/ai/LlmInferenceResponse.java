package tech.kayys.gamelan.plugin.ai;

import java.util.Map;

public record LlmInferenceResponse(
        String text,
        String model,
        LlmUsage usage,
        String finishReason,
        Map<String, Object> metadata) {

    public LlmInferenceResponse {
        text = text != null ? text : "";
        model = model != null ? model : "";
        usage = usage != null ? usage : new LlmUsage(0, 0, 0);
        finishReason = finishReason != null ? finishReason : "";
        metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
    }
}
