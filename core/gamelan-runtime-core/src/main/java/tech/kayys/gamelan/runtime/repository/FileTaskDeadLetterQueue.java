package tech.kayys.gamelan.runtime.repository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.arc.properties.IfBuildProperty;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import tech.kayys.gamelan.scheduler.TaskDeadLetterQueue;

@ApplicationScoped
@IfBuildProperty(name = "gamelan.workflow.persistence.store", stringValue = "file")
public class FileTaskDeadLetterQueue implements TaskDeadLetterQueue {

    private final Path rootDirectory;
    private final ObjectMapper fallbackObjectMapper;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    public FileTaskDeadLetterQueue(
            @ConfigProperty(name = "gamelan.workflow.persistence.file.root", defaultValue = ".gamelan/workflow")
            String workflowRootDirectory,
            @ConfigProperty(name = "gamelan.task-dead-letter.file.root")
            Optional<String> deadLetterRootDirectory) {
        this(deadLetterRootDirectory
                .map(FilePersistenceSupport::root)
                .orElseGet(() -> FilePersistenceSupport.root(workflowRootDirectory).resolve("task-dead-letters")),
                FilePersistenceSupport.objectMapper());
    }

    public FileTaskDeadLetterQueue(Path rootDirectory) {
        this(rootDirectory.toAbsolutePath().normalize(), FilePersistenceSupport.objectMapper());
    }

    FileTaskDeadLetterQueue(Path rootDirectory, ObjectMapper objectMapper) {
        this.rootDirectory = Objects.requireNonNull(rootDirectory, "rootDirectory").toAbsolutePath().normalize();
        this.fallbackObjectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public Uni<Void> publish(DeadLetterTask task) {
        return Uni.createFrom().voidItem().invoke(() -> {
            Path path = deadLetterPath(task.messageId());
            FilePersistenceSupport.withFileLock(rootDirectory, path, () -> {
                FilePersistenceSupport.writeAtomic(rootDirectory, path, task, mapper());
                return null;
            });
        });
    }

    @Override
    public Uni<List<DeadLetterTask>> list(int limit) {
        return list(new DeadLetterQuery(limit, null, null, null, null));
    }

    @Override
    public Uni<List<DeadLetterTask>> list(DeadLetterQuery query) {
        DeadLetterQuery effectiveQuery = query != null ? query : DeadLetterQuery.all();
        return Uni.createFrom().item(() -> allDeadLetters().stream()
                .filter(effectiveQuery::matches)
                .sorted(Comparator.comparing(DeadLetterTask::deadLetteredAt).reversed())
                .limit(effectiveQuery.limit())
                .toList());
    }

    @Override
    public Uni<Long> count() {
        return Uni.createFrom().item(() -> (long) allDeadLetters().size());
    }

    @Override
    public Uni<Long> count(DeadLetterQuery query) {
        DeadLetterQuery effectiveQuery = query != null ? query : DeadLetterQuery.all();
        return Uni.createFrom().item(() -> allDeadLetters().stream()
                .filter(effectiveQuery::matches)
                .count());
    }

    @Override
    public Uni<Optional<DeadLetterTask>> get(String messageId) {
        String normalizedMessageId = normalizeMessageId(messageId);
        if (normalizedMessageId == null) {
            return Uni.createFrom().item(Optional.empty());
        }
        return Uni.createFrom().item(() -> Optional.ofNullable(FilePersistenceSupport.read(
                deadLetterPath(normalizedMessageId),
                DeadLetterTask.class,
                mapper())));
    }

    @Override
    public Uni<Boolean> delete(String messageId) {
        String normalizedMessageId = normalizeMessageId(messageId);
        if (normalizedMessageId == null) {
            return Uni.createFrom().item(false);
        }
        return Uni.createFrom().item(() -> {
            Path path = deadLetterPath(normalizedMessageId);
            return FilePersistenceSupport.withFileLock(rootDirectory, path, () -> {
                boolean exists = Files.isRegularFile(path);
                FilePersistenceSupport.deleteIfExists(rootDirectory, path);
                return exists;
            });
        });
    }

    @Override
    public Uni<Long> clear(DeadLetterQuery query) {
        DeadLetterQuery effectiveQuery = query != null ? query : DeadLetterQuery.all();
        return Uni.createFrom().item(() -> {
            AtomicLong deleted = new AtomicLong();
            FilePersistenceSupport.listJsonFiles(rootDirectory).forEach(path ->
                    FilePersistenceSupport.withFileLock(rootDirectory, path, () -> {
                        DeadLetterTask deadLetter = FilePersistenceSupport.read(path, DeadLetterTask.class, mapper());
                        if (deadLetter != null && effectiveQuery.matches(deadLetter) && Files.isRegularFile(path)) {
                            FilePersistenceSupport.deleteIfExists(rootDirectory, path);
                            deleted.incrementAndGet();
                        }
                        return null;
                    }));
            return deleted.get();
        });
    }

    @Override
    public Uni<Void> clear() {
        return Uni.createFrom().voidItem().invoke(() -> FilePersistenceSupport.listJsonFiles(rootDirectory)
                .forEach(path -> FilePersistenceSupport.withFileLock(rootDirectory, path, () -> {
                    FilePersistenceSupport.deleteIfExists(rootDirectory, path);
                    return null;
                })));
    }

    private List<DeadLetterTask> allDeadLetters() {
        return FilePersistenceSupport.listJsonFiles(rootDirectory).stream()
                .map(path -> FilePersistenceSupport.read(path, DeadLetterTask.class, mapper()))
                .filter(Objects::nonNull)
                .toList();
    }

    private Path deadLetterPath(String messageId) {
        return rootDirectory.resolve(FilePersistenceSupport.fileName(messageId));
    }

    private ObjectMapper mapper() {
        return objectMapper != null ? objectMapper : fallbackObjectMapper;
    }

    private static String normalizeMessageId(String messageId) {
        return messageId != null && !messageId.isBlank() ? messageId.trim() : null;
    }
}
