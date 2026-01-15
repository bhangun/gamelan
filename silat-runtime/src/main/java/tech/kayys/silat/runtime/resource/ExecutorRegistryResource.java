package tech.kayys.silat.runtime.resource;

import java.util.List;
import java.util.Map;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import tech.kayys.silat.model.ExecutorHealthInfo;
import tech.kayys.silat.model.ExecutorInfo;
import tech.kayys.silat.model.NodeId;
import tech.kayys.silat.registry.ExecutorRegistryService;
import tech.kayys.silat.registry.ExecutorStatistics;

@Path("/api/v1/executors")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ExecutorRegistryResource {

    @Inject
    ExecutorRegistryService executorRegistryService;

    @POST
    public Uni<Void> registerExecutor(ExecutorInfo executor) {
        return executorRegistryService.registerExecutor(executor);
    }

    @DELETE
    @Path("/{executorId}")
    public Uni<Void> unregisterExecutor(@PathParam("executorId") String executorId) {
        return executorRegistryService.unregisterExecutor(executorId);
    }

    @POST
    @Path("/{executorId}/heartbeat")
    public Uni<Void> heartbeat(@PathParam("executorId") String executorId) {
        return executorRegistryService.heartbeat(executorId);
    }

    @GET
    @Path("/{executorId}")
    public Uni<ExecutorInfo> getExecutorById(@PathParam("executorId") String executorId) {
        return executorRegistryService.getExecutorById(executorId)
                .map(optional -> optional.orElse(null)); // Return null if not found
    }

    @GET
    public Uni<List<ExecutorInfo>> getAllExecutors(
            @QueryParam("healthyOnly") Boolean healthyOnly,
            @QueryParam("type") String type,
            @QueryParam("communicationType") String communicationType) {

        Uni<List<ExecutorInfo>> result;

        if (healthyOnly != null && healthyOnly) {
            result = executorRegistryService.getHealthyExecutors();
        } else {
            result = executorRegistryService.getAllExecutors();
        }

        // Apply filters if specified
        if (type != null) {
            result = result.map(executors -> executors.stream()
                    .filter(executor -> executor.executorType().equals(type))
                    .toList());
        }

        if (communicationType != null) {
            result = result.map(executors -> executors.stream()
                    .filter(executor -> executor.communicationType().toString().equalsIgnoreCase(communicationType))
                    .toList());
        }

        return result;
    }

    @GET
    @Path("/healthy")
    public Uni<List<ExecutorInfo>> getHealthyExecutors() {
        return executorRegistryService.getHealthyExecutors();
    }

    @GET
    @Path("/count")
    public Uni<Integer> getExecutorCount() {
        return executorRegistryService.getExecutorCount();
    }

    @GET
    @Path("/statistics")
    public Uni<ExecutorStatistics> getStatistics() {
        return executorRegistryService.getStatistics();
    }

    @GET
    @Path("/type/{type}")
    public Uni<List<ExecutorInfo>> getExecutorsByType(@PathParam("type") String type) {
        return executorRegistryService.getExecutorsByType(type);
    }

    @GET
    @Path("/communication-type/{communicationType}")
    public Uni<List<ExecutorInfo>> getExecutorsByCommunicationType(
            @PathParam("communicationType") String communicationType) {
        return executorRegistryService.getExecutorsByCommunicationType(
                tech.kayys.silat.model.CommunicationType.valueOf(communicationType.toUpperCase()));
    }

    @GET
    @Path("/health/{executorId}")
    public Uni<ExecutorHealthInfo> getHealthInfo(@PathParam("executorId") String executorId) {
        return executorRegistryService.getHealthInfo(executorId)
                .map(optional -> optional.orElse(null)); // Return null if not found
    }

    @GET
    @Path("/healthy/{executorId}")
    public Uni<Boolean> isHealthy(@PathParam("executorId") String executorId) {
        return executorRegistryService.isHealthy(executorId);
    }

    @PUT
    @Path("/{executorId}/metadata")
    public Uni<Void> updateExecutorMetadata(@PathParam("executorId") String executorId,
            Map<String, String> metadata) {
        return executorRegistryService.updateExecutorMetadata(executorId, metadata);
    }

    @POST
    @Path("/select/{nodeId}")
    public Uni<ExecutorInfo> getExecutorForNode(@PathParam("nodeId") String nodeId) {
        return executorRegistryService.getExecutorForNode(new NodeId(nodeId))
                .map(optional -> optional.orElse(null)); // Return null if not found
    }
}