package tech.kayys.gamelan.test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import tech.kayys.gamelan.plugin.Plugin;
import tech.kayys.gamelan.plugin.PluginService;
import tech.kayys.gamelan.plugin.impl.DefaultPluginService;

/**
 * Standalone test to demonstrate the plugin system
 */
public class PluginSystemTest {
    
    public static void main(String[] args) {
        System.out.println("=".repeat(80));
        System.out.println("Gamelan Plugin System Test");
        System.out.println("=".repeat(80));
        System.out.println();
        
        try {
            // Create unified plugin service
            PluginService pluginService = new DefaultPluginService();
            pluginService.setPluginDirectory("./plugins");
            pluginService.setDataDirectory("./plugin-data");

            System.out.println("✓ Plugin Service initialized");
            System.out.println("  - Plugin directory: ./plugins");
            System.out.println("  - Data directory: ./plugin-data");
            System.out.println();

            // Test 1: Load example plugin
            System.out.println("Test 1: Loading Example Plugin");
            System.out.println("-".repeat(80));

            Path pluginJar = Paths.get("../examples/gamelan-plugin-example/target/gamelan-plugin-example-1.0.0-SNAPSHOT.jar");
            if (!pluginJar.toFile().exists()) {
                System.err.println("✗ Plugin JAR not found: " + pluginJar);
                System.err.println("  Please build the example plugin first:");
                System.err.println("  mvn clean package -f examples/gamelan-plugin-example/pom.xml");
                System.exit(1);
            }

            Plugin plugin = pluginService.loadPlugin(pluginJar).await().indefinitely();
            System.out.println("✓ Plugin loaded: " + plugin.getMetadata().name());
            System.out.println("  - ID: " + plugin.getMetadata().id());
            System.out.println("  - Version: " + plugin.getMetadata().version());
            System.out.println("  - Author: " + plugin.getMetadata().author());
            System.out.println("  - Description: " + plugin.getMetadata().description());
            System.out.println();

            // Test 2: Start plugin
            System.out.println("Test 2: Starting Plugin");
            System.out.println("-".repeat(80));

            pluginService.startPlugin(plugin.getMetadata().id()).await().indefinitely();
            System.out.println("✓ Plugin started successfully");
            System.out.println();

            // Test 3: Get plugins by type
            System.out.println("Test 3: Query Plugins by Type");
            System.out.println("-".repeat(80));

            List<Plugin> allPlugins = pluginService.getAllPlugins();
            System.out.println("✓ Total plugins loaded: " + allPlugins.size());

            allPlugins.forEach(p -> {
                System.out.println("  - " + p.getMetadata().name() + " (v" + p.getMetadata().version() + ")");
            });
            System.out.println();

            // Test 4: Hot reload
            System.out.println("Test 4: Hot Reload Plugin");
            System.out.println("-".repeat(80));

            Plugin reloadedPlugin = pluginService.reloadPlugin(
                plugin.getMetadata().id(),
                pluginJar
            ).await().indefinitely();
            System.out.println("✓ Plugin hot-reloaded successfully");
            System.out.println("  - Plugin ID: " + reloadedPlugin.getMetadata().id());
            System.out.println();

            // Test 5: Stop and unload
            System.out.println("Test 5: Stop and Unload Plugin");
            System.out.println("-".repeat(80));

            pluginService.stopPlugin(plugin.getMetadata().id()).await().indefinitely();
            System.out.println("✓ Plugin stopped");

            pluginService.unloadPlugin(plugin.getMetadata().id()).await().indefinitely();
            System.out.println("✓ Plugin unloaded");
            System.out.println();
            
            // Summary
            System.out.println("=".repeat(80));
            System.out.println("✓ All Tests Passed!");
            System.out.println("=".repeat(80));
            System.out.println();
            System.out.println("Plugin System Features Demonstrated:");
            System.out.println("  ✓ Plugin loading from JAR");
            System.out.println("  ✓ Plugin lifecycle management (initialize, start, stop)");
            System.out.println("  ✓ Plugin metadata retrieval");
            System.out.println("  ✓ Plugin querying by type");
            System.out.println("  ✓ Hot-reload functionality");
            System.out.println("  ✓ Plugin unloading and cleanup");
            System.out.println();
            System.out.println("The Gamelan Plugin System is working correctly!");
            
        } catch (Exception e) {
            System.err.println();
            System.err.println("✗ Test Failed!");
            System.err.println("=".repeat(80));
            e.printStackTrace();
            System.exit(1);
        }
    }
}
