package tech.kayys.gamelan.plugin.ai;

import java.util.List;
import java.util.Map;

public record LlmInferenceRequest(
        String model,
        String prompt,
        int maxTokens,
        double temperature,
        boolean stream,
        List<String> guardrails,
        Map<String, Object> metadata) {

    public LlmInferenceRequest {
        model = model != null && !model.isBlank() ? model : "default-model";
        prompt = prompt != null ? prompt : "";
        maxTokens = maxTokens > 0 ? maxTokens : 1000;
        guardrails = guardrails != null ? List.copyOf(guardrails) : List.of();
        metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
    }
}
