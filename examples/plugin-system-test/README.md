# Plugin System Test Harness

This is a standalone test that demonstrates the Gamelan Plugin System functionality.

## Prerequisites

1. Build the plugin API:
```bash
cd /Users/bhangun/Workspace/workkayys/Products/Wayang/wayang-platform/wayang-workflow
mvn clean install -pl gamelan-plugin-spi -DskipTests
```

2. Build the example plugin:
```bash
mvn clean package -f examples/gamelan-plugin-example/pom.xml
```

## Running the Test

```bash
cd plugin-system-test
mvn clean compile exec:java
```

## What It Tests

The test demonstrates:

1. **Plugin Loading** - Loading a plugin from a JAR file
2. **Plugin Lifecycle** - Initialize, start, and stop operations
3. **Plugin Metadata** - Retrieving plugin information
4. **Plugin Querying** - Getting plugins by type
5. **Hot Reload** - Reloading a plugin at runtime
6. **Plugin Unloading** - Cleanup and resource management

## Expected Output

You should see output similar to:

```
================================================================================
Gamelan Plugin System Test
================================================================================

✓ Plugin Manager initialized
  - Plugin directory: ./plugins
  - Data directory: ./plugin-data

Test 1: Loading Example Plugin
--------------------------------------------------------------------------------
✓ Plugin loaded: Logging Interceptor Plugin
  - ID: logging-interceptor
  - Version: 1.0.0
  - Author: Gamelan Team
  - Description: Logs all task executions for debugging and monitoring

Test 2: Starting Plugin
--------------------------------------------------------------------------------
✓ Plugin started successfully

Test 3: Query Plugins by Type
--------------------------------------------------------------------------------
✓ Total plugins loaded: 1
  - Logging Interceptor Plugin (v1.0.0)

Test 4: Hot Reload Plugin
--------------------------------------------------------------------------------
✓ Plugin hot-reloaded successfully
  - Plugin ID: logging-interceptor

Test 5: Stop and Unload Plugin
--------------------------------------------------------------------------------
✓ Plugin stopped
✓ Plugin unloaded

================================================================================
✓ All Tests Passed!
================================================================================

Plugin System Features Demonstrated:
  ✓ Plugin loading from JAR
  ✓ Plugin lifecycle management (initialize, start, stop)
  ✓ Plugin metadata retrieval
  ✓ Plugin querying by type
  ✓ Hot-reload functionality
  ✓ Plugin unloading and cleanup

The Gamelan Plugin System is working correctly!
```

## Troubleshooting

If the test fails with "Plugin JAR not found", make sure you've built the example plugin:
```bash
mvn clean package -f ../examples/gamelan-plugin-example/pom.xml
```
