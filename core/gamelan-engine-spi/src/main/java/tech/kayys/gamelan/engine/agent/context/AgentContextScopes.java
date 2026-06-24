package tech.kayys.gamelan.engine.agent.context;

/**
 * Common scopes for agent-local context documents.
 */
public final class AgentContextScopes {

    public static final String WORKSPACE = "workspace";
    public static final String SKILL = "skill";
    public static final String THREAD = "thread";
    public static final String PROMPT = "prompt";

    private AgentContextScopes() {
    }
}
