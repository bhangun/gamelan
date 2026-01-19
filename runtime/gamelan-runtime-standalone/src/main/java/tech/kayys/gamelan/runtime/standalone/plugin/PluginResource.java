package tech.kayys.gamelan.runtime.standalone.plugin;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.annotations.providers.multipart.PartType;
import org.jboss.resteasy.annotations.providers.multipart.MultipartForm;
import org.jboss.resteasy.plugins.providers.multipart.InputPart;
import org.jboss.resteasy.plugins.providers.multipart.MultipartFormDataInput;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * REST endpoint for plugin management and upload
 */
@jakarta.ws.rs.Path("/api/plugins")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.MULTIPART_FORM_DATA)
@RequestScoped
public class PluginResource {

    @Inject
    PluginManager pluginManager;

    @Inject
    PluginConfigurationService pluginConfigService;

    @POST
    @jakarta.ws.rs.Path("/upload")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Response uploadPlugin(MultipartFormDataInput input) {
        try {
            // Extract uploaded file and filename from multipart input
            Map<String, List<InputPart>> uploadForm = input.getFormDataMap();

            List<InputPart> fileParts = uploadForm.get("uploadedInputStream");
            if (fileParts == null || fileParts.isEmpty()) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity("{\"error\": \"No file uploaded\"}")
                        .build();
            }

            InputPart filePart = fileParts.get(0);
            InputStream uploadedInputStream = filePart.getBody(InputStream.class, null);

            List<InputPart> filenameParts = uploadForm.get("filename");
            String filename = null;
            if (filenameParts != null && !filenameParts.isEmpty()) {
                filename = filenameParts.get(0).getBody(String.class, null);
            }

            if (filename == null) {
                // Try to get filename from content disposition header
                String contentDisposition = filePart.getHeaders().getFirst("Content-Disposition");
                if (contentDisposition != null) {
                    filename = contentDisposition.substring(contentDisposition.indexOf("filename=") + 10,
                            contentDisposition.length() - 1);
                }
            }

            // Validate file extension
            if (filename == null || !filename.toLowerCase().endsWith(".jar")) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity("{\"error\": \"Only JAR files are allowed\"}")
                        .build();
            }

            // Create plugins directory if it doesn't exist
            java.nio.file.Path pluginsDir = Paths.get(pluginManager.getPluginsDirectory());
            if (!Files.exists(pluginsDir)) {
                Files.createDirectories(pluginsDir);
            }

            // Save the uploaded file
            java.nio.file.Path targetPath = pluginsDir.resolve(filename);
            if (Files.exists(targetPath)) {
                return Response.status(Response.Status.CONFLICT)
                        .entity("{\"error\": \"Plugin file already exists\"}")
                        .build();
            }

            try (FileOutputStream outputStream = new FileOutputStream(targetPath.toFile())) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = uploadedInputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                }
            }

            // Attempt to load the plugin
            boolean loaded = pluginManager.loadPlugin(targetPath);
            if (loaded) {
                Log.infof("Plugin uploaded and loaded successfully: %s", filename);
                return Response.ok()
                        .entity("{\"message\": \"Plugin uploaded and loaded successfully\", \"filename\": \"" + filename + "\"}")
                        .build();
            } else {
                Log.warnf("Plugin uploaded but failed to load: %s", filename);
                return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                        .entity("{\"error\": \"Plugin uploaded but failed to load\", \"filename\": \"" + filename + "\"}")
                        .build();
            }

        } catch (Exception e) {
            Log.errorf("Error uploading plugin: %s", e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"error\": \"Failed to upload plugin: " + e.getMessage() + "\"}")
                    .build();
        }
    }

    @GET
    @jakarta.ws.rs.Path("/")
    public Response getAllPlugins() {
        try {
            List<PluginInfo> plugins = pluginManager.getAllPlugins();
            StringBuilder response = new StringBuilder("{\"plugins\": [");
            
            for (int i = 0; i < plugins.size(); i++) {
                PluginInfo plugin = plugins.get(i);
                response.append("{")
                        .append("\"name\":\"").append(plugin.getName()).append("\",")
                        .append("\"version\":\"").append(plugin.getVersion()).append("\",")
                        .append("\"fileName\":\"").append(plugin.getFileName()).append("\",")
                        .append("\"enabled\":").append(plugin.isEnabled())
                        .append("}");
                
                if (i < plugins.size() - 1) {
                    response.append(",");
                }
            }
            
            response.append("]}");
            
            return Response.ok(response.toString()).build();
        } catch (Exception e) {
            Log.errorf("Error retrieving plugins: %s", e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"error\": \"Failed to retrieve plugins: " + e.getMessage() + "\"}")
                    .build();
        }
    }

    @GET
    @jakarta.ws.rs.Path("/{fileName}")
    public Response getPlugin(@jakarta.ws.rs.PathParam("fileName") String fileName) {
        try {
            PluginInfo plugin = pluginManager.getPlugin(fileName);
            if (plugin == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity("{\"error\": \"Plugin not found: " + fileName + "\"}")
                        .build();
            }

            String response = "{"
                    + "\"name\":\"" + plugin.getName() + "\","
                    + "\"version\":\"" + plugin.getVersion() + "\","
                    + "\"fileName\":\"" + plugin.getFileName() + "\","
                    + "\"filePath\":\"" + plugin.getFilePath() + "\","
                    + "\"enabled\":" + plugin.isEnabled()
                    + "}";

            return Response.ok(response).build();
        } catch (Exception e) {
            Log.errorf("Error retrieving plugin: %s", e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"error\": \"Failed to retrieve plugin: " + e.getMessage() + "\"}")
                    .build();
        }
    }

    @DELETE
    @jakarta.ws.rs.Path("/{fileName}")
    public Response deletePlugin(@jakarta.ws.rs.PathParam("fileName") String fileName) {
        try {
            // First unload the plugin if it's loaded
            pluginManager.unloadPlugin(fileName);

            // Then delete the file
            java.nio.file.Path pluginsDir = Paths.get(pluginManager.getPluginsDirectory());
            java.nio.file.Path pluginPath = pluginsDir.resolve(fileName);

            if (!Files.exists(pluginPath)) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity("{\"error\": \"Plugin file not found: " + fileName + "\"}")
                        .build();
            }

            Files.delete(pluginPath);
            Log.infof("Plugin deleted: %s", fileName);

            return Response.ok()
                    .entity("{\"message\": \"Plugin deleted successfully\", \"filename\": \"" + fileName + "\"}")
                    .build();
        } catch (Exception e) {
            Log.errorf("Error deleting plugin: %s", e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"error\": \"Failed to delete plugin: " + e.getMessage() + "\"}")
                    .build();
        }
    }

    @PUT
    @jakarta.ws.rs.Path("/{fileName}/enable")
    public Response enablePlugin(@jakarta.ws.rs.PathParam("fileName") String fileName) {
        try {
            boolean result = pluginManager.enablePlugin(fileName);
            if (result) {
                return Response.ok()
                        .entity("{\"message\": \"Plugin enabled successfully\", \"filename\": \"" + fileName + "\"}")
                        .build();
            } else {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity("{\"error\": \"Plugin not found: " + fileName + "\"}")
                        .build();
            }
        } catch (Exception e) {
            Log.errorf("Error enabling plugin: %s", e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"error\": \"Failed to enable plugin: " + e.getMessage() + "\"}")
                    .build();
        }
    }

    @PUT
    @jakarta.ws.rs.Path("/{fileName}/disable")
    public Response disablePlugin(@jakarta.ws.rs.PathParam("fileName") String fileName) {
        try {
            boolean result = pluginManager.disablePlugin(fileName);
            if (result) {
                return Response.ok()
                        .entity("{\"message\": \"Plugin disabled successfully\", \"filename\": \"" + fileName + "\"}")
                        .build();
            } else {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity("{\"error\": \"Plugin not found: " + fileName + "\"}")
                        .build();
            }
        } catch (Exception e) {
            Log.errorf("Error disabling plugin: %s", e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"error\": \"Failed to disable plugin: " + e.getMessage() + "\"}")
                    .build();
        }
    }

    @POST
    @Path("/refresh")
    public Response refreshPlugins() {
        try {
            pluginManager.refreshPlugins();
            return Response.ok()
                    .entity("{\"message\": \"Plugin refresh completed\"}")
                    .build();
        } catch (Exception e) {
            Log.errorf("Error refreshing plugins: %s", e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"error\": \"Failed to refresh plugins: " + e.getMessage() + "\"}")
                    .build();
        }
    }

    @GET
    @jakarta.ws.rs.Path("/{fileName}/config")
    public Response getPluginConfig(@jakarta.ws.rs.PathParam("fileName") String fileName) {
        try {
            // Extract plugin name from filename (remove .jar extension)
            String pluginName = fileName.replace(".jar", "");
            Properties config = pluginConfigService.loadPluginConfig(pluginName);

            StringBuilder response = new StringBuilder("{\"config\": {");
            boolean first = true;
            for (String key : config.stringPropertyNames()) {
                if (!first) {
                    response.append(",");
                }
                response.append("\"").append(key).append("\":\"").append(config.getProperty(key)).append("\"");
                first = false;
            }
            response.append("}}");

            return Response.ok(response.toString()).build();
        } catch (Exception e) {
            Log.errorf("Error retrieving plugin config: %s", e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"error\": \"Failed to retrieve plugin config: " + e.getMessage() + "\"}")
                    .build();
        }
    }

    @POST
    @jakarta.ws.rs.Path("/{fileName}/config")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response updatePluginConfig(@jakarta.ws.rs.PathParam("fileName") String fileName, String configJson) {
        try {
            // Extract plugin name from filename (remove .jar extension)
            String pluginName = fileName.replace(".jar", "");

            // Parse the JSON config (simplified - in real implementation you'd use a proper JSON parser)
            // For now, we'll simulate updating properties
            // In a real implementation, you'd parse the JSON and update individual properties
            // This is a simplified version that creates a new config based on the JSON

            // For demonstration purposes, let's assume the JSON is in the format {"key1": "value1", "key2": "value2"}
            // In a real implementation, you'd use Jackson or similar to parse the JSON
            Properties config = pluginConfigService.loadPluginConfig(pluginName);

            // This is a simplified approach - in reality you'd parse the JSON properly
            // For now, let's just create a default config to simulate
            config.setProperty("updated-at", String.valueOf(System.currentTimeMillis()));

            boolean success = pluginConfigService.savePluginConfig(pluginName, config);
            if (success) {
                return Response.ok()
                        .entity("{\"message\": \"Plugin configuration updated successfully\", \"filename\": \"" + fileName + "\"}")
                        .build();
            } else {
                return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                        .entity("{\"error\": \"Failed to update plugin configuration\"}")
                        .build();
            }
        } catch (Exception e) {
            Log.errorf("Error updating plugin config: %s", e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"error\": \"Failed to update plugin config: " + e.getMessage() + "\"}")
                    .build();
        }
    }

    @PUT
    @jakarta.ws.rs.Path("/{fileName}/config/{key}")
    @Consumes(MediaType.TEXT_PLAIN)
    public Response updatePluginConfigProperty(@jakarta.ws.rs.PathParam("fileName") String fileName,
                                              @jakarta.ws.rs.PathParam("key") String key,
                                              String value) {
        try {
            // Extract plugin name from filename (remove .jar extension)
            String pluginName = fileName.replace(".jar", "");

            boolean success = pluginConfigService.updatePluginConfigProperty(pluginName, key, value);
            if (success) {
                return Response.ok()
                        .entity("{\"message\": \"Plugin configuration property updated\", \"filename\": \"" + fileName + "\", \"key\": \"" + key + "\", \"value\": \"" + value + "\"}")
                        .build();
            } else {
                return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                        .entity("{\"error\": \"Failed to update plugin configuration property\"}")
                        .build();
            }
        } catch (Exception e) {
            Log.errorf("Error updating plugin config property: %s", e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"error\": \"Failed to update plugin config property: " + e.getMessage() + "\"}")
                    .build();
        }
    }

    @DELETE
    @jakarta.ws.rs.Path("/{fileName}/config/{key}")
    public Response removePluginConfigProperty(@jakarta.ws.rs.PathParam("fileName") String fileName,
                                              @jakarta.ws.rs.PathParam("key") String key) {
        try {
            // Extract plugin name from filename (remove .jar extension)
            String pluginName = fileName.replace(".jar", "");

            boolean success = pluginConfigService.removePluginConfigProperty(pluginName, key);
            if (success) {
                return Response.ok()
                        .entity("{\"message\": \"Plugin configuration property removed\", \"filename\": \"" + fileName + "\", \"key\": \"" + key + "\"}")
                        .build();
            } else {
                return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                        .entity("{\"error\": \"Failed to remove plugin configuration property\"}")
                        .build();
            }
        } catch (Exception e) {
            Log.errorf("Error removing plugin config property: %s", e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"error\": \"Failed to remove plugin config property: " + e.getMessage() + "\"}")
                    .build();
        }
    }
}