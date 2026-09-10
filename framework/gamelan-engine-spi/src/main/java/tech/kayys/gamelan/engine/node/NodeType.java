package tech.kayys.gamelan.engine.node;

/**
 * LLM-native node types for AI workflow orchestration.
 * <p>
 * Extends the base NodeType enum with AI-specific step types that understand
 * LLM concepts like streaming, tokens, context windows, guardrails, and tools.
 *
 * @since 0.1.0
 */
public enum NodeType {
    // Existing types
    TASK,
    DECISION,
    PARALLEL,
    AGGREGATE,
    HUMAN_TASK,
    SUB_WORKFLOW,
    EVENT_WAIT,
    TIMER,
    COMPENSATION,
    EXECUTOR,

    // ── LLM-Native Types ───────────────────────────────────────────────

    /**
     * LLM inference step - calls gollek inference engine.
     * Configuration:
     * - model: model ID or path
     * - prompt: prompt template
     * - stream: enable streaming (default: false)
     * - maxTokens: maximum tokens to generate
     * - temperature: sampling temperature
     * - guardrails: list of guardrail checks to apply
     */
    LLM_INFERENCE,

    /**
     * RAG retrieval step - retrieves relevant context from vector DB.
     * Configuration:
     * - vectorStore: vector database connection
     * - query: retrieval query
     * - topK: number of results to retrieve
     * - filters: metadata filters
     */
    RAG_RETRIEVAL,

    /**
     * RAG generation step - combines retrieval with LLM generation.
     * Configuration:
     * - model: model ID
     * - promptTemplate: template with {{context}} and {{query}} placeholders
     * - vectorStore: vector database connection
     * - topK: number of context documents
     * - guardrails: list of guardrail checks
     */
    RAG_GENERATION,

    /**
     * Agent loop step - ReAct/Tool-use pattern.
     * Configuration:
     * - model: model ID
     * - tools: list of available tools
     * - maxIterations: maximum reasoning loops
     * - systemPrompt: agent system prompt
     */
    AGENT_LOOP,

    /**
     * Guardrail check step - validates input/output against policies.
     * Configuration:
     * - policy: policy type (NoPII, Toxicity, Custom, etc.)
     * - input/output: what to check
     * - action: action on violation (block, redact, warn)
     */
    GUARDRAIL_CHECK,

    /**
     * Human approval step - waits for human review/approval.
     * Configuration:
     * - role: required approval role
     * - timeout: timeout duration
     * - message: approval request message
     */
    HUMAN_APPROVAL,

    /**
     * A/B test step - routes traffic to model variants.
     * Configuration:
     * - variantA: first model/config
     * - variantB: second model/config
     * - splitRatio: traffic split (0.0-1.0 for variant A)
     * - metric: evaluation metric
     */
    AB_TEST,

    /**
     * LLM evaluation step - evaluates output using LLM-as-judge.
     * Configuration:
     * - criteria: evaluation criteria
     * - model: judge model (optional, defaults to same as generation)
     * - scale: scoring scale (1-5, 1-10, etc.)
     */
    LLM_EVALUATION
}
