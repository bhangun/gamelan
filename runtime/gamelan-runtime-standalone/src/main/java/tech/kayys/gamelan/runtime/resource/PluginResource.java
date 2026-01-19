package tech.kayys.gamelan.runtime.resource;

import java.util.List;

import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import tech.kayys.gamelan.plugin.Plugin;
import tech.kayys.gamelan.plugin.PluginService;

@Path("/api/v1/plugins")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class PluginResource {

    @Inject
    PluginService pluginService;

    @GET
    public Uni<List<Plugin>> getAllPlugins() {
        return Uni.createFrom().item(pluginService.getAllPlugins());
    }

    @GET
    @Path("/{pluginId}")
    public Uni<Plugin> getPlugin(@PathParam("pluginId") String pluginId) {
        return Uni.createFrom().item(pluginService.getPlugin(pluginId).orElse(null));
    }

    @POST
    @Path("/{pluginId}/start")
    public Uni<Void> startPlugin(@PathParam("pluginId") String pluginId) {
        return pluginService.startPlugin(pluginId);
    }

    @POST
    @Path("/{pluginId}/stop")
    public Uni<Void> stopPlugin(@PathParam("pluginId") String pluginId) {
        return pluginService.stopPlugin(pluginId);
    }

    @GET
    @Path("/types/{pluginType}")
    public Uni<List<Plugin>> getPluginsByType(@PathParam("pluginType") String pluginType) {
        // This would require reflection to determine plugin types
        // For now, we'll return all plugins
        return Uni.createFrom().item(pluginService.getAllPlugins());
    }

    @GET
    @Path("/status")
    public Uni<List<PluginStatusInfo>> getPluginStatuses() {
        List<Plugin> plugins = pluginService.getAllPlugins();
        List<PluginStatusInfo> statuses = plugins.stream()
                .map(plugin -> new PluginStatusInfo(
                        plugin.getMetadata().id(),
                        plugin.getMetadata().name(),
                        plugin.getMetadata().version(),
                        "ACTIVE" // Simplified status
                ))
                .toList();
        return Uni.createFrom().item(statuses);
    }

    public record PluginStatusInfo(String id, String name, String version, String status) {
    }
}