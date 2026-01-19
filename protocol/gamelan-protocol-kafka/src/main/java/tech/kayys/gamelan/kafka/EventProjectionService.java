package tech.kayys.gamelan.kafka;

import tech.kayys.gamelan.engine.repository.WorkflowRunRepository;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Event projection service
 */
@ApplicationScoped
public class EventProjectionService {

    @Inject
    WorkflowRunRepository repository;

    public Uni<Void> project(WorkflowEventMessage event) {
        // Project event to read model
        // Update materialized views
        // Update analytics tables
        return Uni.createFrom().voidItem();
    }
}
