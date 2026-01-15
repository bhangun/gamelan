package tech.kayys.silat.test.client;

import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import tech.kayys.silat.sdk.client.SilatClient;
import tech.kayys.silat.model.NodeDefinition;
import tech.kayys.silat.model.NodeId;
import tech.kayys.silat.model.NodeType;
import tech.kayys.silat.model.Transition;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Path("/test-workflow")
public class TestWorkflowResource {

    @Inject
    SilatClient silatClient;

    @GET
    @Path("/trigger")
    @Produces(MediaType.TEXT_PLAIN)
    public Uni<String> triggerWorkflow() {
        String workflowId = "simulation-workflow-" + UUID.randomUUID();
        
        // 1. Define Step 1
        NodeId step1Id = new NodeId("step1");
        NodeDefinition step1 = new NodeDefinition(
                step1Id,
                "Process Data - Step 1",
                NodeType.EXECUTOR,
                "test-executor",
                Map.of("action", "validate", "payload", "Initial data"),
                List.of(), // No dependencies -> Start node
                List.of(new Transition(new NodeId("step2"), null, Transition.TransitionType.SUCCESS)),
                null,
                java.time.Duration.ofSeconds(30),
                true
        );

        // 2. Define Step 2
        NodeDefinition step2 = new NodeDefinition(
                new NodeId("step2"),
                "Finalize Data - Step 2",
                NodeType.EXECUTOR,
                "test-executor",
                Map.of("action", "archive", "priority", "high"),
                List.of(step1Id), // Depends on Step 1
                List.of(), // End node
                null,
                java.time.Duration.ofSeconds(30),
                false
        );

        // 3. Register and Start
        return silatClient.workflows().create(workflowId)
                .version("1.0.0")
                .addNode(step1)
                .addNode(step2)
                .execute()
                .chain(def -> {
                    System.out.println("Workflow definition created: " + def.id());
                    return silatClient.runs().create(def.id().value())
                            .version(def.version())
                            .input("source", "Simulation Trigger")
                            .executeAndStart();
                })
                .map(run -> "Simulation started! Run ID: " + run.getRunId() + "\nCheck Silat Engine logs for execution details.");
    }
}
