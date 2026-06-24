package tech.kayys.gamelan.engine.agent.context;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Bounded page of agent-context documents with a cursor when another page is known to exist.
 */
public record AgentContextPage(
        List<AgentContextDocument> documents,
        AgentContextCursor nextCursor) {

    public AgentContextPage {
        documents = documents != null ? List.copyOf(documents) : List.of();
    }

    public static AgentContextPage from(AgentContextQuery query, List<AgentContextDocument> documents) {
        Objects.requireNonNull(query, "AgentContextQuery cannot be null");
        List<AgentContextDocument> safeDocuments = documents != null ? List.copyOf(documents) : List.of();
        boolean hasContinuation = hasContinuation(query, safeDocuments);
        List<AgentContextDocument> pageDocuments = hasContinuation
                ? safeDocuments.subList(0, query.maxResults())
                : safeDocuments;
        return new AgentContextPage(pageDocuments, nextCursor(pageDocuments, hasContinuation));
    }

    public boolean hasContinuation() {
        return nextCursor != null;
    }

    public Optional<AgentContextQuery> nextQuery(AgentContextQuery query) {
        Objects.requireNonNull(query, "AgentContextQuery cannot be null");
        if (nextCursor == null) {
            return Optional.empty();
        }
        Integer maxResults = query.maxResults() != null ? query.maxResults() : documents.size();
        return Optional.of(new AgentContextQuery(
                query.tenantId(),
                query.workspaceId(),
                query.scope(),
                query.pathPrefix(),
                maxResults,
                nextCursor));
    }

    private static boolean hasContinuation(AgentContextQuery query, List<AgentContextDocument> documents) {
        return query.maxResults() != null && documents.size() > query.maxResults();
    }

    private static AgentContextCursor nextCursor(List<AgentContextDocument> documents, boolean hasContinuation) {
        if (!hasContinuation || documents.isEmpty()) {
            return null;
        }
        AgentContextKey lastKey = documents.get(documents.size() - 1).key();
        return new AgentContextCursor(lastKey.scope(), lastKey.path());
    }
}
