package tech.kayys.gamelan.sdk.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.engine.node.NodeExecutionResult;
import tech.kayys.gamelan.engine.node.NodeExecutionTask;
import tech.kayys.gamelan.grpc.v1.CommunicationType;
import tech.kayys.gamelan.grpc.v1.HeartbeatRequest;
import tech.kayys.gamelan.grpc.v1.RegisterExecutorRequest;
import tech.kayys.gamelan.sdk.executor.core.WorkflowExecutor;

class GrpcExecutorTransportTest {

    @Test
    void registrationRequestsCreateOneStreamIdentityPerExecutorType() {
        GrpcExecutorTransport transport = new GrpcExecutorTransport();
        try {
            List<RegisterExecutorRequest> requests = transport.registrationRequests(List.of(
                    executor("agent.planner", 2, "plan", "tool"),
                    executor("business.invoice", 4, "invoice")));

            assertEquals(2, requests.size());
            Map<String, RegisterExecutorRequest> byType = requests.stream()
                    .collect(Collectors.toMap(RegisterExecutorRequest::getExecutorType, request -> request));

            RegisterExecutorRequest planner = byType.get("agent.planner");
            RegisterExecutorRequest invoice = byType.get("business.invoice");
            assertNotEquals(planner.getExecutorId(), invoice.getExecutorId());
            assertEquals("", planner.getEndpoint());
            assertEquals(CommunicationType.COMMUNICATION_TYPE_GRPC, planner.getCommunicationType());
            assertEquals(List.of("plan", "tool"), planner.getSupportedNodeTypesList());
            assertEquals(2, planner.getMaxConcurrentTasks());
            assertEquals(4, invoice.getMaxConcurrentTasks());
            assertEquals(GrpcExecutorTransport.STREAM_DELIVERY,
                    planner.getMetadataOrThrow(GrpcExecutorTransport.METADATA_GRPC_DELIVERY));
            assertEquals(GrpcExecutorTransport.STREAM_DELIVERY,
                    planner.getMetadataOrThrow(GrpcExecutorTransport.METADATA_TASK_DELIVERY));
            assertEquals("agent.planner",
                    planner.getMetadataOrThrow(GrpcExecutorTransport.METADATA_EXECUTOR_TYPE));
        } finally {
            transport.cleanup();
        }
    }

    @Test
    void registrationRequestsDeduplicateExecutorTypes() {
        GrpcExecutorTransport transport = new GrpcExecutorTransport();
        try {
            List<RegisterExecutorRequest> requests = transport.registrationRequests(List.of(
                    executor("agent.planner", 2, "plan"),
                    executor("agent.planner", 8, "duplicate")));

            assertEquals(1, requests.size());
            assertEquals("agent.planner", requests.get(0).getExecutorType());
            assertEquals(2, requests.get(0).getMaxConcurrentTasks());
        } finally {
            transport.cleanup();
        }
    }

    @Test
    void activeTaskIdsForRenewalExpiresTasksAfterMaxDuration() {
        GrpcExecutorTransport transport = new GrpcExecutorTransport();
        try {
            Instant now = Instant.parse("2026-05-26T00:00:00Z");
            transport.ackRenewalMaxDuration = Duration.ofHours(1);
            transport.trackStreamedTask("task-active", now.minus(Duration.ofMinutes(30)));
            transport.trackStreamedTask("task-expired", now.minus(Duration.ofHours(2)));

            List<String> taskIds = transport.activeTaskIdsForRenewal(now);

            assertEquals(List.of("task-active"), taskIds);
            assertTrue(transport.hasActiveStreamedTask("task-active"));
            assertFalse(transport.hasActiveStreamedTask("task-expired"));
        } finally {
            transport.cleanup();
        }
    }

    @Test
    void completeStreamedTaskStopsFutureRenewal() {
        GrpcExecutorTransport transport = new GrpcExecutorTransport();
        try {
            transport.trackStreamedTask("task-1", Instant.now());

            transport.completeStreamedTask("task-1");

            assertFalse(transport.hasActiveStreamedTask("task-1"));
        } finally {
            transport.cleanup();
        }
    }

    @Test
    void heartbeatRequestIncludesCurrentTaskCount() {
        GrpcExecutorTransport transport = new GrpcExecutorTransport();
        try {
            transport.trackStreamedTask("task-1", "executor-1", Instant.now());
            transport.trackStreamedTask("task-2", "executor-1", Instant.now());
            transport.trackStreamedTask("task-3", "executor-2", Instant.now());

            HeartbeatRequest request = transport.heartbeatRequest("executor-1");

            assertEquals("executor-1", request.getExecutorId());
            assertEquals(2, request.getCurrentTaskCount());
            assertEquals(2, request.getHealth().getCurrentTasks());
            assertEquals("BUSY", request.getHealth().getStatus());
        } finally {
            transport.cleanup();
        }
    }

    private static TestWorkflowExecutor executor(
            String executorType,
            int maxConcurrentTasks,
            String... supportedNodeTypes) {
        return new TestWorkflowExecutor(executorType, maxConcurrentTasks, supportedNodeTypes);
    }

    private record TestWorkflowExecutor(
            String executorType,
            int maxConcurrentTasks,
            String[] supportedNodeTypes) implements WorkflowExecutor {

        @Override
        public Uni<NodeExecutionResult> execute(NodeExecutionTask task) {
            return Uni.createFrom().failure(new UnsupportedOperationException("not used"));
        }

        @Override
        public String getExecutorType() {
            return executorType;
        }

        @Override
        public int getMaxConcurrentTasks() {
            return maxConcurrentTasks;
        }

        @Override
        public String[] getSupportedNodeTypes() {
            return supportedNodeTypes;
        }

        @Override
        public String getVersion() {
            return "1.2.3";
        }

        @Override
        public String getDescription() {
            return "test executor";
        }
    }
}
