package tech.kayys.gamelan.runtime.resource;

import io.quarkus.arc.DefaultBean;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupDeadLetterAuditEvent;
import tech.kayys.gamelan.engine.event.WorkflowRunWakeupDeadLetterAuditSink;

@ApplicationScoped
@DefaultBean
public class NoopWorkflowRunWakeupDeadLetterAuditSink implements WorkflowRunWakeupDeadLetterAuditSink {

    @Override
    public Uni<Void> append(WorkflowRunWakeupDeadLetterAuditEvent event) {
        return Uni.createFrom().voidItem();
    }
}
