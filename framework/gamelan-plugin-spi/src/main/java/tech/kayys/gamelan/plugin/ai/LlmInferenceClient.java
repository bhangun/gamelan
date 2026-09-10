package tech.kayys.gamelan.plugin.ai;

import io.smallrye.mutiny.Uni;

/**
 * Provider-neutral LLM inference contract for AI node handlers.
 * Implementations can bridge to Gollek, remote model gateways, or mock engines.
 */
public interface LlmInferenceClient {

    Uni<LlmInferenceResponse> infer(LlmInferenceRequest request);
}
