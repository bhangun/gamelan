package tech.kayys.gamelan.core.event;

import java.util.List;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import tech.kayys.gamelan.engine.context.WorkflowContext;
import tech.kayys.gamelan.engine.event.EventPublisher;
import tech.kayys.gamelan.engine.event.ExecutionEvent;
import tech.kayys.gamelan.engine.extension.ExtensionRegistry;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.persistence.PersistenceProvider;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;

@ApplicationScoped
public class DefaultEventPublisher implements EventPublisher {

    @Inject
    PersistenceProvider persistence;
    @Inject
    ExtensionRegistry extensionRegistry;

    @Override
    public void publish(String eventType, Object payload, WorkflowContext wf) {

        persistence.appendEvent(
                wf.runId(),
                eventType,
                payload);

        extensionRegistry
                .interceptors()
                .forEach(p -> p.onEvent(eventType, payload, wf));
    }

    @Override
    public void publishSystem(String eventType, Object payload) {
        extensionRegistry
                .interceptors()
                .forEach(p -> p.onSystemEvent(eventType, payload));
    }

    @Override
    public Uni<Void> publish(List<ExecutionEvent> events) {
        // TODO: Implement batch publishing
        return Uni.createFrom().voidItem();
    }

    @Override
    public Uni<Void> publishRetry(WorkflowRunId runId, NodeId nodeId) {
        // TODO: Implement retry publishing
        return Uni.createFrom().voidItem();
    }
}
