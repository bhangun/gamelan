package tech.kayys.gamelan.engine;

import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.engine.workflow.WorkflowRun;

@FunctionalInterface
interface LockedOperation<T> {
    Uni<T> apply(WorkflowRun run);
}