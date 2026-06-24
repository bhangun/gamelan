package tech.kayys.gamelan.sdk.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.protobuf.Empty;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tech.kayys.gamelan.engine.node.InputDefinition;
import tech.kayys.gamelan.engine.node.NodeDefinition;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.node.NodeType;
import tech.kayys.gamelan.engine.node.OutputDefinition;
import tech.kayys.gamelan.engine.run.RetryPolicy;
import tech.kayys.gamelan.engine.saga.CompensationPolicy;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinition;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinitionId;
import tech.kayys.gamelan.engine.workflow.WorkflowMetadata;
import tech.kayys.gamelan.engine.workflow.WorkflowMode;
import tech.kayys.gamelan.grpc.GrpcWorkflowDefinitionMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

class GrpcWorkflowDefinitionClientTest {

    private CapturingGateway gateway;
    private GrpcWorkflowDefinitionClient client;

    @BeforeEach
    void setUp() {
        GamelanClientConfig config = GamelanClientConfig.builder()
                .endpoint("localhost:9090")
                .grpc()
                .tenantId("test-tenant")
                .build();
        gateway = new CapturingGateway();
        client = new GrpcWorkflowDefinitionClient(config, gateway);
    }

    @Test
    void createWorkflowMapsDomainDefinitionToGrpcRequest() {
        WorkflowDefinition created = client.createWorkflow(sampleDefinition()).await().indefinitely();

        assertEquals("test-tenant", gateway.createRequest.getTenantId());
        assertEquals("agent-workflow", gateway.createRequest.getName());
        assertEquals("2.1.0", gateway.createRequest.getVersion());
        assertEquals(1, gateway.createRequest.getNodesCount());
        assertEquals("java", gateway.createRequest.getNodes(0).getConfiguration().getFieldsOrThrow("language").getStringValue());
        assertEquals("input", gateway.createRequest.getInputsOrThrow("prompt").getType());
        assertEquals("text", gateway.createRequest.getOutputsOrThrow("answer").getType());
        assertEquals(3, gateway.createRequest.getDefaultRetryPolicy().getMaxAttempts());
        assertEquals("agent-workflow", created.name());
        assertEquals("test-tenant", created.tenantId().value());
        assertEquals("agentic", created.metadata().labels().get("domain"));
    }

    @Test
    void getByNameUsesNameFieldInsteadOfDefinitionId() {
        WorkflowDefinition definition = client.getWorkflowByName("agent-workflow").await().indefinitely();

        assertEquals("agent-workflow", gateway.getRequest.getName());
        assertEquals("", gateway.getRequest.getDefinitionId());
        assertEquals("agent-workflow", definition.name());
    }

    @Test
    void listAndDeleteUseTenantScopedRequests() {
        assertEquals(1, client.listWorkflows().await().indefinitely().size());
        assertEquals("test-tenant", gateway.listRequest.getTenantId());
        assertEquals(true, gateway.listRequest.getActiveOnly());

        assertEquals(1, client.listWorkflows(false).await().indefinitely().size());
        assertEquals(false, gateway.listRequest.getActiveOnly());

        client.deleteWorkflow("wf-1").await().indefinitely();
        assertEquals("test-tenant", gateway.deleteRequest.getTenantId());
        assertEquals("wf-1", gateway.deleteRequest.getDefinitionId());
    }

    @Test
    void closeStateRejectsCallsSynchronously() {
        client.close();
        assertThrows(IllegalStateException.class, () -> client.getWorkflow("any"));
    }

    private static WorkflowDefinition sampleDefinition() {
        NodeDefinition node = new NodeDefinition(
                NodeId.of("plan"),
                "Plan",
                NodeType.TASK,
                "local",
                Map.of("language", "java"),
                List.of(),
                List.of(),
                RetryPolicy.none(),
                Duration.ofSeconds(30),
                true);

        return new WorkflowDefinition(
                WorkflowDefinitionId.of("wf-1"),
                TenantId.of("test-tenant"),
                "agent-workflow",
                "2.1.0",
                "Agent orchestration",
                WorkflowMode.FLOW,
                List.of(node),
                Map.of("prompt", new InputDefinition("prompt", "input", true, Map.of("template", "ship"), "Prompt")),
                Map.of("answer", new OutputDefinition("answer", "text", "Answer")),
                new WorkflowMetadata(Map.of("domain", "agentic"), Map.of(), Instant.EPOCH, "test"),
                new RetryPolicy(3, Duration.ofSeconds(1), Duration.ofSeconds(10), 2.0, List.of("timeout")),
                CompensationPolicy.disabled());
    }

    private static final class CapturingGateway implements GrpcWorkflowDefinitionClient.WorkflowDefinitionGrpcGateway {
        private tech.kayys.gamelan.grpc.v1.CreateDefinitionRequest createRequest;
        private tech.kayys.gamelan.grpc.v1.GetDefinitionRequest getRequest;
        private tech.kayys.gamelan.grpc.v1.ListDefinitionsRequest listRequest;
        private tech.kayys.gamelan.grpc.v1.DeleteDefinitionRequest deleteRequest;

        @Override
        public Uni<tech.kayys.gamelan.grpc.v1.DefinitionResponse> createDefinition(
                tech.kayys.gamelan.grpc.v1.CreateDefinitionRequest request) {
            this.createRequest = request;
            return Uni.createFrom().item(GrpcWorkflowDefinitionMapper.toProtoDefinitionResponse(sampleDefinition(), true));
        }

        @Override
        public Uni<tech.kayys.gamelan.grpc.v1.DefinitionResponse> getDefinition(
                tech.kayys.gamelan.grpc.v1.GetDefinitionRequest request) {
            this.getRequest = request;
            return Uni.createFrom().item(GrpcWorkflowDefinitionMapper.toProtoDefinitionResponse(sampleDefinition(), true));
        }

        @Override
        public Uni<tech.kayys.gamelan.grpc.v1.ListDefinitionsResponse> listDefinitions(
                tech.kayys.gamelan.grpc.v1.ListDefinitionsRequest request) {
            this.listRequest = request;
            return Uni.createFrom().item(tech.kayys.gamelan.grpc.v1.ListDefinitionsResponse.newBuilder()
                    .addDefinitions(GrpcWorkflowDefinitionMapper.toProtoDefinitionResponse(sampleDefinition(), true))
                    .setTotal(1)
                    .build());
        }

        @Override
        public Uni<Empty> deleteDefinition(tech.kayys.gamelan.grpc.v1.DeleteDefinitionRequest request) {
            this.deleteRequest = request;
            return Uni.createFrom().item(Empty.getDefaultInstance());
        }
    }
}
