package tech.kayys.gamelan.engine.agent.context;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import io.smallrye.mutiny.Uni;

/**
 * Persistence abstraction for text-based agent context.
 *
 * Intended payloads include AGENTS.md, SKILL.md, prompt logs, thread history,
 * and other local-first context documents that may later be projected to a
 * server/cloud store.
 */
public interface AgentContextStore {

    Uni<AgentContextDocument> save(AgentContextDocument document);

    Uni<Optional<AgentContextDocument>> load(AgentContextKey key);

    Uni<AgentContextDocument> append(AgentContextKey key, String content, Map<String, String> metadata);

    default Uni<AgentContextDocument> append(AgentContextKey key, String content) {
        return append(key, content, Map.of());
    }

    Uni<List<AgentContextDocument>> list(AgentContextQuery query);

    default Uni<AgentContextPage> listPage(AgentContextQuery query) {
        AgentContextQuery requestedQuery = Objects.requireNonNull(query, "AgentContextQuery cannot be null");
        AgentContextQuery storageQuery = continuationProbeQuery(requestedQuery);
        return list(storageQuery).map(documents -> AgentContextPage.from(requestedQuery, documents));
    }

    Uni<Void> delete(AgentContextKey key);

    private static AgentContextQuery continuationProbeQuery(AgentContextQuery query) {
        if (query.maxResults() == null || query.maxResults() == Integer.MAX_VALUE) {
            return query;
        }
        return new AgentContextQuery(
                query.tenantId(),
                query.workspaceId(),
                query.scope(),
                query.pathPrefix(),
                query.maxResults() + 1,
                query.after());
    }
}
