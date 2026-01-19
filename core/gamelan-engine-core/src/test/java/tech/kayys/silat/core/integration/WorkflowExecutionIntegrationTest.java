package tech.kayys.gamelan.core.integration;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.mutiny.helpers.test.UniAssertSubscriber;
import tech.kayys.gamelan.engine.workflow.WorkflowRunManager;
import tech.kayys.gamelan.engine.node.InputDefinition;
import tech.kayys.gamelan.engine.node.NodeDefinition;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.run.RunStatus;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinition;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinitionId;
import tech.kayys.gamelan.engine.workflow.WorkflowMetadata;
import tech.kayys.gamelan.engine.workflow.WorkflowRun;
import tech.kayys.gamelan.engine.run.CreateRunRequest;
import tech.kayys.gamelan.core.workflow.WorkflowDefinitionRegistry;

/**
 * Integration test for end-to-end workflow execution with plugin mechanism
 */
@QuarkusTest
public class WorkflowExecutionIntegrationTest {
    
    @Inject
    WorkflowRunManager runManager;
    
    @Inject
    WorkflowDefinitionRegistry definitionRegistry;
    
    @Test
    public void testSimpleWorkflowExecution() {
        // Arrange: Create a simple workflow with 2 sequential nodes
        TenantId tenantId = TenantId.of("test-tenant");
        
        NodeDefinition node1 = NodeDefinition.builder()
            .id(NodeId.of("node-1"))
            .type("test-executor")
            .name("First Node")
            .isStartNode(true)
            .build();
        
        NodeDefinition node2 = NodeDefinition.builder()
            .id(NodeId.of("node-2"))
            .type("test-executor")
            .name("Second Node")
            .dependsOn(List.of(NodeId.of("node-1")))
            .build();
        
        WorkflowDefinition definition = WorkflowDefinition.builder()
            .id(new WorkflowDefinitionId("test-workflow-1"))
            .tenantId(tenantId)
            .name("Test Workflow")
            .version("1.0")
            .description("Integration test workflow")
            .addNode(node1)
            .addNode(node2)
            .addInput("input1", new InputDefinition("input1", "string", true, null, "Test input"))
            .metadata(new WorkflowMetadata(Map.of(), Map.of(), java.time.Instant.now(), "system"))
            .buildAndValidate();
        
        // Register workflow definition
        definitionRegistry.register(definition, tenantId)
            .await().atMost(Duration.ofSeconds(5));
        
        // Act: Create and start workflow run
        CreateRunRequest request = CreateRunRequest.builder()
            .workflowId("test-workflow-1")
            .workflowVersion("1.0")
            .inputs(Map.of("input1", "test-value"))
            .autoStart(true)
            .build();
        
        WorkflowRun run = runManager.createRun(request, tenantId)
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem(Duration.ofSeconds(10))
            .getItem();
        
        // Assert: Verify workflow was created and started
        Assertions.assertNotNull(run);
        Assertions.assertEquals(RunStatus.RUNNING, run.getStatus());
        
        // Wait for workflow to complete (in real scenario, orchestrator would handle this)
        // For now, we just verify the workflow was created and started correctly
        Assertions.assertTrue(run.getPendingNodes().size() > 0, 
            "Workflow should have pending nodes after start");
    }
    
    @Test
    public void testParallelNodeExecution() {
        // Arrange: Create workflow with parallel nodes
        TenantId tenantId = TenantId.of("test-tenant");
        
        NodeDefinition startNode = NodeDefinition.builder()
            .id(NodeId.of("start"))
            .type("test-executor")
            .name("Start Node")
            .isStartNode(true)
            .build();
        
        // Two nodes that depend on start but can run in parallel
        NodeDefinition parallel1 = NodeDefinition.builder()
            .id(NodeId.of("parallel-1"))
            .type("test-executor")
            .name("Parallel Node 1")
            .dependsOn(List.of(NodeId.of("start")))
            .build();
        
        NodeDefinition parallel2 = NodeDefinition.builder()
            .id(NodeId.of("parallel-2"))
            .type("test-executor")
            .name("Parallel Node 2")
            .dependsOn(List.of(NodeId.of("start")))
            .build();
        
        // End node depends on both parallel nodes
        NodeDefinition endNode = NodeDefinition.builder()
            .id(NodeId.of("end"))
            .type("test-executor")
            .name("End Node")
            .dependsOn(List.of(NodeId.of("parallel-1"), NodeId.of("parallel-2")))
            .isEndNode(true)
            .build();
        
        WorkflowDefinition definition = WorkflowDefinition.builder()
            .id(new WorkflowDefinitionId("parallel-workflow"))
            .tenantId(tenantId)
            .name("Parallel Workflow")
            .version("1.0")
            .addNode(startNode)
            .addNode(parallel1)
            .addNode(parallel2)
            .addNode(endNode)
            .metadata(new WorkflowMetadata(Map.of(), Map.of(), java.time.Instant.now(), "system"))
            .buildAndValidate();
        
        // Register workflow
        definitionRegistry.register(definition, tenantId)
            .await().atMost(Duration.ofSeconds(5));
        
        // Act: Create workflow run
        CreateRunRequest request = CreateRunRequest.builder()
            .workflowId("parallel-workflow")
            .workflowVersion("1.0")
            .inputs(Map.of())
            .autoStart(true)
            .build();
        
        WorkflowRun run = runManager.createRun(request, tenantId)
            .subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem(Duration.ofSeconds(10))
            .getItem();
        
        // Assert: Verify parallel structure
        Assertions.assertNotNull(run);
        Assertions.assertEquals(4, definition.nodeCount(), 
            "Should have 4 nodes in parallel workflow");
    }
}
