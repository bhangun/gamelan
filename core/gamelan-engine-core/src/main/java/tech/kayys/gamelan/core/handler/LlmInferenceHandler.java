package tech.kayys.gamelan.core.handler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tech.kayys.gamelan.engine.node.NodeExecutionContext;
import tech.kayys.gamelan.engine.node.NodeResult;
import tech.kayys.gamelan.engine.node.NodeTypeHandler;
import tech.kayys.gamelan.plugin.ai.LlmInferenceClient;
import tech.kayys.gamelan.plugin.ai.LlmInferenceRequest;
import tech.kayys.gamelan.plugin.ai.LlmInferenceResponse;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.enterprise.inject.Instance;

import java.util.Map;
import java.util.HashMap;
import java.util.List;

/**
 * Node type handler for LLM inference steps in Gamelan workflows.
 * <p>
 * This handler delegates to a provider-neutral inference client so Gollek,
 * remote gateways, or test engines can back LLM workflow nodes.
 * <p>
 * <b>Configuration:</b>
 * <pre>
 * {
 *   "nodeType": "LLM_INFERENCE",
 *   "configuration": {
 *     "model": "llama-3-70b",
 *     "prompt": "Summarize: {{input}}",
 *     "stream": false,
 *     "maxTokens": 1000,
 *     "temperature": 0.7,
 *     "guardrails": ["NoPII", "ToxicityCheck"]
 *   }
 * }
 * </pre>
     *
 * @since 0.1.0
 */
@ApplicationScoped
public class LlmInferenceHandler implements NodeTypeHandler {

    private static final Logger LOG = LoggerFactory.getLogger(LlmInferenceHandler.class);

    @Inject
    Instance<LlmInferenceClient> inferenceClients;

    @Override
    public String nodeType() {
        return "LLM_INFERENCE";
    }

    @Override
    public NodeResult execute(NodeExecutionContext ctx) {
        if (inferenceClients == null || inferenceClients.isUnsatisfied()) {
            return NodeResult.failure("No LLM inference client registered for LLM_INFERENCE");
        }

        Map<String, Object> config = nodeConfiguration(ctx);

        // Extract configuration
        String model = getString(config, "model", "default-model");
        String promptTemplate = getString(config, "prompt", "{{input}}");
        boolean stream = getBoolean(config, "stream", false);
        int maxTokens = getInt(config, "maxTokens", 1000);
        double temperature = getDouble(config, "temperature", 0.7);
        @SuppressWarnings("unchecked")
        List<String> guardrails = (List<String>) config.getOrDefault("guardrails", List.of());

        // Resolve prompt with workflow variables
        String prompt = resolvePrompt(promptTemplate, ctx);

        // Apply guardrails
        if (!guardrails.isEmpty()) {
            for (String guardrail : guardrails) {
                GuardrailResult result = applyGuardrail(guardrail, prompt);
                if (!result.passed()) {
                    LOG.warn("Guardrail {} failed: {}", guardrail, result.reason());
                    return NodeResult.failure("Guardrail " + guardrail + " failed: " + result.reason());
                }
            }
        }

        // Execute inference
        try {
            LlmInferenceRequest request = new LlmInferenceRequest(
                    model,
                    prompt,
                    maxTokens,
                    temperature,
                    stream,
                    guardrails,
                    Map.of("nodeId", ctx.node().nodeId().value()));

            LlmInferenceResponse response = inferenceClients.get()
                    .infer(request)
                    .await().atMost(java.time.Duration.ofMinutes(5));

            // Build result
            Map<String, Object> output = new HashMap<>();
            output.put("text", response.text());
            output.put("model", response.model());
            output.put("usage", response.usage());
            output.put("finishReason", response.finishReason());

            LOG.info("LLM inference completed: model={}, tokens={}", model, response.usage().totalTokens());

            return NodeResult.success(output);
        } catch (Exception e) {
            LOG.error("LLM inference failed: model={}", model, e);
            return NodeResult.failure(e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> nodeConfiguration(NodeExecutionContext ctx) {
        Object config = ctx.node().metadata().get("configuration");
        if (config instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    private String resolvePrompt(String template, NodeExecutionContext ctx) {
        String resolved = template;

        // Replace {{variable}} with workflow variables
        for (Map.Entry<String, Object> entry : ctx.variables().entrySet()) {
            String placeholder = "{{" + entry.getKey() + "}}";
            if (resolved.contains(placeholder)) {
                resolved = resolved.replace(placeholder,
                        entry.getValue() != null ? entry.getValue().toString() : "");
            }
        }

        return resolved;
    }

    private GuardrailResult applyGuardrail(String guardrailType, String text) {
        // Delegate to guardrail service
        // For now, return success (implement guardrails separately)
        return new GuardrailResult(true, null);
    }

    private String getString(Map<String, Object> map, String key, String defaultValue) {
        Object value = map.get(key);
        return value != null ? value.toString() : defaultValue;
    }

    private boolean getBoolean(Map<String, Object> map, String key, boolean defaultValue) {
        Object value = map.get(key);
        return value != null ? Boolean.parseBoolean(value.toString()) : defaultValue;
    }

    private int getInt(Map<String, Object> map, String key, int defaultValue) {
        Object value = map.get(key);
        return value != null ? Integer.parseInt(value.toString()) : defaultValue;
    }

    private double getDouble(Map<String, Object> map, String key, double defaultValue) {
        Object value = map.get(key);
        return value != null ? Double.parseDouble(value.toString()) : defaultValue;
    }

    record GuardrailResult(boolean passed, String reason) {}
}
