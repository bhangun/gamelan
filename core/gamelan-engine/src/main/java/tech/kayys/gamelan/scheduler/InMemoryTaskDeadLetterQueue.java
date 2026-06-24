package tech.kayys.gamelan.scheduler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.quarkus.arc.DefaultBean;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
@DefaultBean
public class InMemoryTaskDeadLetterQueue implements TaskDeadLetterQueue {

    private static final Logger LOG = LoggerFactory.getLogger(InMemoryTaskDeadLetterQueue.class);

    private final CopyOnWriteArrayList<DeadLetterTask> deadLetters = new CopyOnWriteArrayList<>();

    @Override
    public Uni<Void> publish(DeadLetterTask task) {
        deadLetters.add(task);
        LOG.error("Dead-lettered task message={}, run={}, node={}, reason={}, deferCount={}",
                task.messageId(),
                task.task().runId().value(),
                task.task().nodeId().value(),
                task.reason(),
                task.deferCount());
        return Uni.createFrom().voidItem();
    }

    @Override
    public Uni<List<DeadLetterTask>> list(int limit) {
        return list(new DeadLetterQuery(limit, null, null, null, null));
    }

    @Override
    public Uni<List<DeadLetterTask>> list(DeadLetterQuery query) {
        DeadLetterQuery effectiveQuery = query != null ? query : DeadLetterQuery.all();
        List<DeadLetterTask> matches = List.copyOf(deadLetters).stream()
                .filter(effectiveQuery::matches)
                .toList();
        int fromIndex = Math.max(0, matches.size() - effectiveQuery.limit());
        List<DeadLetterTask> recent = new ArrayList<>(matches.subList(fromIndex, matches.size()));
        Collections.reverse(recent);
        return Uni.createFrom().item(List.copyOf(recent));
    }

    @Override
    public Uni<Long> count() {
        return Uni.createFrom().item((long) deadLetters.size());
    }

    @Override
    public Uni<Long> count(DeadLetterQuery query) {
        DeadLetterQuery effectiveQuery = query != null ? query : DeadLetterQuery.all();
        long count = deadLetters.stream()
                .filter(effectiveQuery::matches)
                .count();
        return Uni.createFrom().item(count);
    }

    @Override
    public Uni<Optional<DeadLetterTask>> get(String messageId) {
        String normalizedMessageId = normalizeMessageId(messageId);
        if (normalizedMessageId == null) {
            return Uni.createFrom().item(Optional.empty());
        }
        return Uni.createFrom().item(deadLetters.reversed().stream()
                .filter(entry -> Objects.equals(entry.messageId(), normalizedMessageId))
                .findFirst());
    }

    @Override
    public Uni<Boolean> delete(String messageId) {
        String normalizedMessageId = normalizeMessageId(messageId);
        if (normalizedMessageId == null) {
            return Uni.createFrom().item(false);
        }
        boolean removed = deadLetters.removeIf(entry -> Objects.equals(entry.messageId(), normalizedMessageId));
        return Uni.createFrom().item(removed);
    }

    @Override
    public Uni<Long> clear(DeadLetterQuery query) {
        DeadLetterQuery effectiveQuery = query != null ? query : DeadLetterQuery.all();
        long matchingEntries = deadLetters.stream()
                .filter(effectiveQuery::matches)
                .count();
        deadLetters.removeIf(effectiveQuery::matches);
        return Uni.createFrom().item(matchingEntries);
    }

    @Override
    public Uni<Void> clear() {
        deadLetters.clear();
        return Uni.createFrom().voidItem();
    }

    private static String normalizeMessageId(String messageId) {
        return messageId != null && !messageId.isBlank() ? messageId.trim() : null;
    }
}
