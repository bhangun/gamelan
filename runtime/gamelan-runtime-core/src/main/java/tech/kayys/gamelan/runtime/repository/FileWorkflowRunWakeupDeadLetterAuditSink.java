package tech.kayys.gamelan.runtime.repository;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.arc.properties.IfBuildProperty;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupDeadLetterAuditEvent;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupDeadLetterAuditSink;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupDeadLetterAuditSink.AuditPurgePolicy;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupDeadLetterAuditSink.AuditPurgeResult;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupDeadLetterAuditSink.AuditQuery;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupDeadLetterAuditSink.AuditSummary;

@ApplicationScoped
@IfBuildProperty(name = "gamelan.workflow.persistence.store", stringValue = "file")
public class FileWorkflowRunWakeupDeadLetterAuditSink implements WorkflowRunWakeupDeadLetterAuditSink {

    private final Path rootDirectory;
    private final ObjectMapper fallbackObjectMapper;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    public FileWorkflowRunWakeupDeadLetterAuditSink(
            @ConfigProperty(name = "gamelan.workflow.persistence.file.root", defaultValue = ".gamelan/workflow")
            String workflowRootDirectory,
            @ConfigProperty(name = "gamelan.workflow.wakeup.dead-letter-audit.file.root")
            java.util.Optional<String> auditRootDirectory) {
        this(auditRootDirectory
                .map(FilePersistenceSupport::root)
                .orElseGet(() -> FilePersistenceSupport.root(workflowRootDirectory)
                        .resolve("workflow-wakeup-dead-letter-audit")),
                FilePersistenceSupport.objectMapper());
    }

    public FileWorkflowRunWakeupDeadLetterAuditSink(Path rootDirectory) {
        this(rootDirectory.toAbsolutePath().normalize(), FilePersistenceSupport.objectMapper());
    }

    FileWorkflowRunWakeupDeadLetterAuditSink(Path rootDirectory, ObjectMapper objectMapper) {
        this.rootDirectory = Objects.requireNonNull(rootDirectory, "rootDirectory").toAbsolutePath().normalize();
        this.fallbackObjectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public Uni<Void> append(WorkflowRunWakeupDeadLetterAuditEvent event) {
        Objects.requireNonNull(event, "Workflow wake-up dead-letter audit event cannot be null");
        return Uni.createFrom().voidItem().invoke(() -> FilePersistenceSupport.writeAtomic(
                rootDirectory,
                auditPath(event),
                event,
                mapper()));
    }

    @Override
    public Uni<List<WorkflowRunWakeupDeadLetterAuditEvent>> entries(AuditQuery query) {
        AuditQuery effectiveQuery = query != null ? query : AuditQuery.all();
        return Uni.createFrom().item(() -> allRecords().stream()
                .map(StoredAudit::event)
                .filter(effectiveQuery::matches)
                .sorted(byOccurredAtDescending())
                .limit(effectiveQuery.limit())
                .toList());
    }

    @Override
    public Uni<Long> count(AuditQuery query) {
        AuditQuery effectiveQuery = query != null ? query : AuditQuery.all();
        return Uni.createFrom().item(() -> allRecords().stream()
                .map(StoredAudit::event)
                .filter(effectiveQuery::matches)
                .count());
    }

    @Override
    public Uni<AuditSummary> summary(AuditQuery query) {
        AuditQuery effectiveQuery = query != null ? query : AuditQuery.all();
        return Uni.createFrom().item(() -> AuditSummary.from(allRecords().stream()
                .map(StoredAudit::event)
                .filter(effectiveQuery::matches)
                .toList()));
    }

    @Override
    public Uni<AuditPurgeResult> purge(AuditPurgePolicy policy) {
        AuditPurgePolicy effectivePolicy = policy != null ? policy : AuditPurgePolicy.disabled();
        if (!effectivePolicy.hasRetentionCriteria()) {
            return Uni.createFrom().item(AuditPurgeResult.empty(effectivePolicy.dryRun()));
        }
        return Uni.createFrom().item(() -> {
            List<StoredAudit> candidates = purgeCandidates(effectivePolicy, Instant.now());
            List<String> auditIds = candidates.stream()
                    .map(StoredAudit::auditId)
                    .toList();
            int purged = 0;
            if (!effectivePolicy.dryRun()) {
                for (StoredAudit candidate : candidates) {
                    FilePersistenceSupport.deleteIfExists(rootDirectory, candidate.path());
                    purged++;
                }
            }
            return new AuditPurgeResult(
                    candidates.size(),
                    effectivePolicy.dryRun() ? 0 : purged,
                    effectivePolicy.dryRun(),
                    auditIds);
        });
    }

    private List<StoredAudit> purgeCandidates(AuditPurgePolicy policy, Instant now) {
        return allRecords().stream()
                .filter(record -> policy.query().matches(record.event()))
                .sorted(Comparator.comparing((StoredAudit record) -> record.event().occurredAt()).reversed())
                .skip(policy.retainLatest() >= 0 ? policy.retainLatest() : 0)
                .filter(record -> policy.matchesAge(record.event(), now))
                .toList();
    }

    private List<StoredAudit> allRecords() {
        return FilePersistenceSupport.listJsonFiles(rootDirectory).stream()
                .map(path -> new StoredAudit(
                        path,
                        FilePersistenceSupport.read(
                                path,
                                WorkflowRunWakeupDeadLetterAuditEvent.class,
                                mapper())))
                .filter(record -> record.event() != null)
                .toList();
    }

    private static Comparator<WorkflowRunWakeupDeadLetterAuditEvent> byOccurredAtDescending() {
        return Comparator.comparing(WorkflowRunWakeupDeadLetterAuditEvent::occurredAt).reversed();
    }

    private Path auditPath(WorkflowRunWakeupDeadLetterAuditEvent event) {
        Instant occurredAt = event.occurredAt() != null ? event.occurredAt() : Instant.now();
        String auditKey = occurredAt.toEpochMilli() + "-" + UUID.randomUUID();
        return rootDirectory.resolve(FilePersistenceSupport.fileName(auditKey));
    }

    private ObjectMapper mapper() {
        return objectMapper != null ? objectMapper : fallbackObjectMapper;
    }

    private record StoredAudit(
            Path path,
            WorkflowRunWakeupDeadLetterAuditEvent event) {

        private String auditId() {
            String fileName = path.getFileName().toString();
            return fileName.endsWith(".json") ? fileName.substring(0, fileName.length() - 5) : fileName;
        }
    }
}
