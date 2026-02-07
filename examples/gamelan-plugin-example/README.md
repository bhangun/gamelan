# Gamelan Example Plugin

This is an example plugin demonstrating the Gamelan plugin mechanism.

## What it does

`LoggingPlugin` is an `ExecutionInterceptorPlugin` that logs workflow execution events:
- Logs before node execution
- Logs after node execution (with success status)
- Logs errors during execution

## Building

```bash
mvn clean package
```

## Deploying

Copy the JAR to your Gamelan plugins directory:

```bash
cp target/gamelan-plugin-example-*.jar /path/to/gamelan/plugins/
```

## Configuration

The plugin is automatically discovered via Java ServiceLoader.

## Code Structure

- `LoggingPlugin.java` - Main plugin implementation
- `META-INF/services/tech.kayys.gamelan.engine.plugin.GamelanPlugin` - ServiceLoader registration

## Extending

Use this as a template for your own plugins:

1. Copy this project structure
2. Rename the plugin class
3. Update `pom.xml` with your plugin details
4. Implement your custom logic in the interceptor methods
5. Update the ServiceLoader file with your class name

## See Also

- [Plugin Development Guide](../../docs/plugin-development-guide.md)
- [Gamelan Plugin API](../../core/gamelan-plugin-spi)
