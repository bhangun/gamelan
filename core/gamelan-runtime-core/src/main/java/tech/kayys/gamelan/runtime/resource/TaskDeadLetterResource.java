package tech.kayys.gamelan.runtime.resource;

import java.util.ArrayList;
import java.util.List;

import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import tech.kayys.gamelan.scheduler.TaskDeadLetterQueue;
import tech.kayys.gamelan.scheduler.TaskQueue;
import tech.kayys.gamelan.scheduler.TaskQueueMetadata;

@Path("/api/v1/task-dead-letters")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class TaskDeadLetterResource {

    @Inject
    TaskDeadLetterQueue deadLetterQueue;

    @Inject
    TaskQueue taskQueue;

    @GET
    public Uni<List<TaskDeadLetterQueue.DeadLetterTask>> list(
            @QueryParam("limit") Integer limit,
            @QueryParam("runId") String runId,
            @QueryParam("nodeId") String nodeId,
            @QueryParam("tenantId") String tenantId,
            @QueryParam("reason") String reason) {
        return deadLetterQueue.list(query(limit, runId, nodeId, tenantId, reason));
    }

    @GET
    @Path("/count")
    public Uni<Long> count(
            @QueryParam("runId") String runId,
            @QueryParam("nodeId") String nodeId,
            @QueryParam("tenantId") String tenantId,
            @QueryParam("reason") String reason) {
        return deadLetterQueue.count(query(null, runId, nodeId, tenantId, reason));
    }

    @DELETE
    public Uni<Void> clear(
            @QueryParam("runId") String runId,
            @QueryParam("nodeId") String nodeId,
            @QueryParam("tenantId") String tenantId,
            @QueryParam("reason") String reason) {
        TaskDeadLetterQueue.DeadLetterQuery query = query(null, runId, nodeId, tenantId, reason);
        if (!query.hasFilters()) {
            return deadLetterQueue.clear();
        }
        return deadLetterQueue.clear(query).replaceWithVoid();
    }

    @DELETE
    @Path("/{messageId}")
    public Uni<Void> delete(@PathParam("messageId") String messageId) {
        return deadLetterQueue.delete(messageId)
                .flatMap(deleted -> Boolean.TRUE.equals(deleted)
                        ? Uni.createFrom().voidItem()
                        : Uni.createFrom().failure(
                                new NotFoundException("Task dead letter not found: " + messageId)));
    }

    @POST
    @Path("/{messageId}/requeue")
    public Uni<TaskDeadLetterQueue.DeadLetterTask> requeue(@PathParam("messageId") String messageId) {
        return deadLetterQueue.get(messageId)
                .flatMap(deadLetter -> deadLetter
                        .map(this::requeue)
                        .orElseGet(() -> Uni.createFrom().failure(
                                new NotFoundException("Task dead letter not found: " + messageId))));
    }

    @POST
    @Path("/requeue")
    public Uni<DeadLetterRequeueResponse> requeueMatching(
            @QueryParam("limit") Integer limit,
            @QueryParam("runId") String runId,
            @QueryParam("nodeId") String nodeId,
            @QueryParam("tenantId") String tenantId,
            @QueryParam("reason") String reason,
            @QueryParam("all") Boolean all) {
        TaskDeadLetterQueue.DeadLetterQuery query = query(limit, runId, nodeId, tenantId, reason);
        if (!query.hasFilters() && !Boolean.TRUE.equals(all)) {
            return Uni.createFrom().failure(new BadRequestException(
                    "Bulk task dead-letter requeue requires a filter or all=true"));
        }
        return deadLetterQueue.list(query)
                .flatMap(this::requeueSelected);
    }

    private Uni<TaskDeadLetterQueue.DeadLetterTask> requeue(TaskDeadLetterQueue.DeadLetterTask deadLetter) {
        return taskQueue.enqueue(TaskQueueMetadata.withoutQueueMetadata(deadLetter.task()))
                .flatMap(ignored -> deadLetterQueue.delete(deadLetter.messageId()))
                .flatMap(deleted -> Boolean.TRUE.equals(deleted)
                        ? Uni.createFrom().item(deadLetter)
                        : Uni.createFrom().failure(new IllegalStateException(
                                "Task dead letter was requeued but cleanup was not confirmed: "
                                        + deadLetter.messageId())));
    }

    private Uni<DeadLetterRequeueResponse> requeueSelected(List<TaskDeadLetterQueue.DeadLetterTask> deadLetters) {
        RequeueAccumulator accumulator = new RequeueAccumulator(deadLetters.size());
        Uni<RequeueAccumulator> chain = Uni.createFrom().item(accumulator);
        for (TaskDeadLetterQueue.DeadLetterTask deadLetter : deadLetters) {
            chain = chain.flatMap(current -> {
                if (current.hasFailure()) {
                    current.skip(deadLetter);
                    return Uni.createFrom().item(current);
                }
                return requeue(deadLetter)
                        .map(requeued -> current.requeued(requeued))
                        .onFailure().recoverWithItem(error -> current.failed(deadLetter, error));
            });
        }
        return chain.map(RequeueAccumulator::response);
    }

    private static TaskDeadLetterQueue.DeadLetterQuery query(
            Integer limit,
            String runId,
            String nodeId,
            String tenantId,
            String reason) {
        return new TaskDeadLetterQueue.DeadLetterQuery(
                limit != null ? limit : 100,
                runId,
                nodeId,
                tenantId,
                reason);
    }

    public record DeadLetterRequeueResponse(
            int selected,
            int requeued,
            int failed,
            int skipped,
            List<String> requeuedMessageIds,
            List<DeadLetterRequeueFailure> failures) {
    }

    public record DeadLetterRequeueFailure(
            String messageId,
            String error) {
    }

    private static final class RequeueAccumulator {
        private final int selected;
        private final List<String> requeuedMessageIds = new ArrayList<>();
        private final List<DeadLetterRequeueFailure> failures = new ArrayList<>();
        private int skipped;

        private RequeueAccumulator(int selected) {
            this.selected = selected;
        }

        private boolean hasFailure() {
            return !failures.isEmpty();
        }

        private RequeueAccumulator requeued(TaskDeadLetterQueue.DeadLetterTask deadLetter) {
            requeuedMessageIds.add(deadLetter.messageId());
            return this;
        }

        private RequeueAccumulator failed(TaskDeadLetterQueue.DeadLetterTask deadLetter, Throwable error) {
            failures.add(new DeadLetterRequeueFailure(deadLetter.messageId(), errorMessage(error)));
            return this;
        }

        private void skip(TaskDeadLetterQueue.DeadLetterTask ignored) {
            skipped++;
        }

        private DeadLetterRequeueResponse response() {
            return new DeadLetterRequeueResponse(
                    selected,
                    requeuedMessageIds.size(),
                    failures.size(),
                    skipped,
                    List.copyOf(requeuedMessageIds),
                    List.copyOf(failures));
        }

        private static String errorMessage(Throwable error) {
            if (error == null) {
                return "unknown";
            }
            String message = error.getMessage();
            return message == null || message.isBlank() ? error.getClass().getName() : message;
        }
    }
}
