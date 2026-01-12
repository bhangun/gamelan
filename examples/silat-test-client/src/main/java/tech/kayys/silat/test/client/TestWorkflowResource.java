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
        String workflowId = "test-workflow-" + UUID.randomUUID();
        String nodeName = "simple-test-task";

        // 1. Create Node Definition directly
        NodeDefinition node = new NodeDefinition(
                new NodeId(nodeName),
                "Test Task",
                NodeType.EXECUTOR,
                "test-executor", // Must match @Executor(executorType)
                Map.of("message", "Hello Silat"),
                List.of(), // dependsOn
                List.of(), // transitions
                null, // retry
                null, // timeout
                false // critical
        );

        // 2. Register workflow
        return silatClient.workflows().create(workflowId)
                .version("1.0.0")
                .addNode(node)
                .execute()
                .chain(def -> {
                    // 3. Start run
                    return silatClient.runs().create(def.id().value()) // Use definition ID or name
                            .version(def.version())
                            .input("message", "Hello from Test Client!")
                            .executeAndStart();
                })
                .map(run -> "Workflow started with Run ID: " + run.getRunId());
    }
}
