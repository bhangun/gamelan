package tech.kayys.silat.engine;

import io.smallrye.mutiny.Uni;
import tech.kayys.silat.model.WorkflowRun;

@FunctionalInterface
interface LockedOperation<T> {
    Uni<T> apply(WorkflowRun run);
}