package tech.kayys.gamelan.dispatcher;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.quarkus.redis.datasource.stream.StreamMessage;
import tech.kayys.gamelan.engine.execution.ExecutionToken;
import tech.kayys.gamelan.engine.node.NodeExecutionTask;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;

class RedisGrpcTaskStreamBrokerTest {

    @Test
    void filterActiveAckLeasesSkipsReclaimedMessagesWhoseAckLeaseIsStillActive() {
        RedisGrpcTaskStreamBroker broker = new RedisGrpcTaskStreamBroker();
        StreamMessage<String, String, NodeExecutionTask> active = message("1-0", task("run-1", "node-1"));
        StreamMessage<String, String, NodeExecutionTask> expired = message("2-0", task("run-1", "node-2"));
        StreamMessage<String, String, NodeExecutionTask> untracked = message("3-0", task("run-1", "node-3"));
        StreamMessage<String, String, NodeExecutionTask> malformed = new StreamMessage<>("tasks", "4-0", Map.of());

        List<StreamMessage<String, String, NodeExecutionTask>> filtered = broker.filterActiveAckLeases(
                List.of(active, expired, untracked, malformed),
                Map.of(
                        "run-1:node-1:1", 1_000D,
                        "run-1:node-2:1", 100D),
                900D);

        assertEquals(List.of(expired, untracked, malformed), filtered);
    }

    private StreamMessage<String, String, NodeExecutionTask> message(String messageId, NodeExecutionTask task) {
        return new StreamMessage<>("tasks", messageId, Map.of("payload", task));
    }

    private NodeExecutionTask task(String runId, String nodeId) {
        WorkflowRunId workflowRunId = WorkflowRunId.of(runId);
        NodeId workflowNodeId = NodeId.of(nodeId);
        return new NodeExecutionTask(
                workflowRunId,
                workflowNodeId,
                1,
                new ExecutionToken(
                        "token-" + nodeId,
                        workflowRunId,
                        workflowNodeId,
                        1,
                        Instant.now().plusSeconds(60)),
                Map.of(),
                null);
    }
}
