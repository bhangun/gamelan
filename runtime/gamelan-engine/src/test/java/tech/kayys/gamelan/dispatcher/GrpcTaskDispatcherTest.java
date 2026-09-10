package tech.kayys.gamelan.dispatcher;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.stub.StreamObserver;
import io.smallrye.mutiny.Uni;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tech.kayys.gamelan.engine.execution.ExecutionToken;
import tech.kayys.gamelan.engine.executor.ExecutorInfo;
import tech.kayys.gamelan.engine.node.NodeExecutionTask;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.protocol.CommunicationType;
import tech.kayys.gamelan.engine.run.RetryPolicy;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;

class GrpcTaskDispatcherTest {

    private static final WorkflowRunId RUN_ID = WorkflowRunId.of("run-1");
    private static final NodeId NODE_ID = NodeId.of("node-1");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private GrpcTaskDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dispatcher = new GrpcTaskDispatcher();
        dispatcher.objectMapper = objectMapper;
    }

    @Test
    void buildRequestSerializesNestedContextValuesAsJsonStrings() throws Exception {
        Map<String, Object> workflowVariables = new LinkedHashMap<>();
        workflowVariables.put("topic", "orders");
        workflowVariables.put("priority", 7);

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("language", "javascript");
        context.put("enabled", true);
        context.put("missing", null);
        context.put("tags", List.of("agent", "local"));
        context.put(NodeExecutionTask.WORKFLOW_VARIABLES_KEY, workflowVariables);

        ExecutionRequest request = dispatcher.buildRequest(task(context), executor());

        assertEquals("javascript", request.getVariablesOrThrow("language"));
        assertEquals("true", request.getVariablesOrThrow("enabled"));
        assertEquals("null", request.getVariablesOrThrow("missing"));
        assertEquals(
                List.of("agent", "local"),
                objectMapper.readValue(request.getVariablesOrThrow("tags"), List.class));

        Map<?, ?> parsedWorkflowVariables = objectMapper.readValue(
                request.getVariablesOrThrow(NodeExecutionTask.WORKFLOW_VARIABLES_KEY),
                Map.class);
        assertEquals("orders", parsedWorkflowVariables.get("topic"));
        assertEquals(7, ((Number) parsedWorkflowVariables.get("priority")).intValue());
    }

    @Test
    void ackObserverCompletesOnlyAfterAcceptedAck() {
        assertDoesNotThrow(() -> ackUni(observer -> {
            observer.onNext(ExecutionAck.newBuilder()
                    .setAccepted(true)
                    .build());
            observer.onCompleted();
        }).await().indefinitely());
    }

    @Test
    void ackObserverFailsRejectedAck() {
        TaskDispatchException exception = assertThrows(TaskDispatchException.class, () -> ackUni(observer -> {
            observer.onNext(ExecutionAck.newBuilder()
                    .setAccepted(false)
                    .setCode(503)
                    .setMessage("executor busy")
                    .build());
            observer.onCompleted();
        }).await().indefinitely());

        assertEquals(503, exception.statusCode());
        assertEquals("executor busy", exception.responseBody());
    }

    @Test
    void ackObserverFailsWhenExecutorCompletesWithoutAck() {
        TaskDispatchException exception = assertThrows(
                TaskDispatchException.class,
                () -> ackUni(StreamObserver::onCompleted).await().indefinitely());

        assertEquals(502, exception.statusCode());
        assertEquals("missing execution ack", exception.responseBody());
    }

    private static Uni<Void> ackUni(Consumer<StreamObserver<ExecutionAck>> action) {
        return Uni.createFrom().emitter(emitter -> {
            StreamObserver<ExecutionAck> observer = GrpcTaskDispatcher.ackObserver(emitter);
            action.accept(observer);
        });
    }

    private static NodeExecutionTask task(Map<String, Object> context) {
        return new NodeExecutionTask(
                RUN_ID,
                NODE_ID,
                2,
                new ExecutionToken("token-1", RUN_ID, NODE_ID, 2, Instant.now().plusSeconds(60)),
                context,
                RetryPolicy.none());
    }

    private static ExecutorInfo executor() {
        return new ExecutorInfo(
                "executor-1",
                "agent",
                CommunicationType.GRPC,
                "localhost:9000",
                Duration.ofSeconds(30),
                Map.of());
    }
}
