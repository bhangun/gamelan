package tech.kayys.gamelan.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.engine.node.NodeExecutionResult;
import tech.kayys.gamelan.engine.node.NodeExecutionResults;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.node.NodeResultHandlingOutcome;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunId;
import tech.kayys.gamelan.engine.workflow.WorkflowRunManager;

class KafkaResultConsumerTest {

    @Test
    void consumeResultSubmitsThroughOutcomeAwareBoundary() throws Exception {
        RecordingRunManager runManager = new RecordingRunManager();
        KafkaResultConsumer consumer = new KafkaResultConsumer();
        consumer.runManager = runManager.proxy();

        consumer.consumeResult(new TaskResultMessage(
                "run-1",
                "tenant-a",
                "node-1",
                1,
                "COMPLETED",
                Map.of("answer", 42),
                null,
                "execution-token",
                Instant.now()));

        assertTrue(runManager.completed.await(2, TimeUnit.SECONDS));
        assertEquals(List.of(WorkflowRunId.of("run-1")), runManager.runIds);
        assertEquals(List.of(TenantId.of("tenant-a")), runManager.tenantIds);
        assertEquals(NodeId.of("node-1"), runManager.results.getFirst().nodeId());
        assertEquals(1, runManager.outcomeCalls);
    }

    private static final class RecordingRunManager {
        private final java.util.List<WorkflowRunId> runIds = new java.util.ArrayList<>();
        private final java.util.List<TenantId> tenantIds = new java.util.ArrayList<>();
        private final java.util.List<NodeExecutionResult> results = new java.util.ArrayList<>();
        private final CountDownLatch completed = new CountDownLatch(1);
        private int outcomeCalls;

        private WorkflowRunManager proxy() {
            return (WorkflowRunManager) Proxy.newProxyInstance(
                    WorkflowRunManager.class.getClassLoader(),
                    new Class<?>[] { WorkflowRunManager.class },
                    (proxy, method, args) -> {
                        if ("onNodeExecutionCompletedWithOutcome".equals(method.getName())) {
                            NodeExecutionResult result = (NodeExecutionResult) args[0];
                            TenantId tenantId = args.length > 2 ? (TenantId) args[1] : null;
                            runIds.add(result.runId());
                            tenantIds.add(tenantId);
                            results.add(result);
                            outcomeCalls++;
                            completed.countDown();
                            return Uni.createFrom().item(new NodeResultHandlingOutcome(
                                    result.runId(),
                                    tenantId,
                                    result.nodeId(),
                                    result.attempt(),
                                    NodeExecutionResults.Acceptance.ACCEPT,
                                    true,
                                    true,
                                    true,
                                    false));
                        }
                        throw new UnsupportedOperationException(method.getName() + " is not used by this test");
                    });
        }
    }
}
