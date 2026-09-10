package tech.kayys.gamelan.dag;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import tech.kayys.gamelan.engine.node.NodeDefinition;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.node.NodeType;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinition;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinitionId;
import tech.kayys.gamelan.engine.workflow.WorkflowMode;

class DagSchedulerServiceTest {

    @Test
    void ordersReadyNodesByTopo() {
        WorkflowDefinition def = WorkflowDefinition.builder()
                .id(WorkflowDefinitionId.of("wf"))
                .tenantId(TenantId.of("tenant"))
                .name("wf")
                .version("1")
                .description("test")
                .mode(WorkflowMode.DAG)
                .nodes(List.of(
                        node("A"),
                        node("B", "A"),
                        node("C", "A")))
                .build();

        DefaultDagSchedulerService service = new DefaultDagSchedulerService();
        List<NodeId> ordered = service.orderReadyNodes(def, List.of(NodeId.of("C"), NodeId.of("B")));

        assertEquals(List.of(NodeId.of("B"), NodeId.of("C")), ordered);
    }

    private static NodeDefinition node(String id, String... deps) {
        NodeDefinition.Builder builder = NodeDefinition.builder()
                .id(NodeId.of(id))
                .type(NodeType.TASK)
                .executorType("test");
        for (String dep : deps) {
            builder.addDependency(NodeId.of(dep));
        }
        return builder.build();
    }
}
