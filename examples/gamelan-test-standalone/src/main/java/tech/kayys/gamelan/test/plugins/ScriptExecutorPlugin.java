package tech.kayys.gamelan.test.plugins;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.execution.NodeExecutionResult;
import tech.kayys.gamelan.execution.NodeExecutionTask;
import tech.kayys.gamelan.model.ErrorInfo;
import tech.kayys.gamelan.plugin.PluginContext;
import tech.kayys.gamelan.plugin.PluginException;
import tech.kayys.gamelan.plugin.PluginMetadata;
import tech.kayys.gamelan.plugin.PluginMetadataBuilder;
import tech.kayys.gamelan.plugin.executor.ExecutorPlugin;
import tech.kayys.gamelan.sdk.executor.SimpleNodeExecutionResult;

/**
 * Example executor plugin for script execution
 * 
 * Handles tasks of type "script" and executes scripts in various languages
 * using Java's ScriptEngine API.
 * 
 * Supported languages: JavaScript (Nashorn/GraalVM), Groovy, Python (Jython)
 * 
 * Example task inputs:
 * - language: "javascript"
 * - script: "function add(a, b) { return a + b; } add(1, 2);"
 * - context: {"a": 1, "b": 2} (optional, variables to inject into script)
 */
public class ScriptExecutorPlugin implements ExecutorPlugin {
    
    private static final Logger LOG = LoggerFactory.getLogger(ScriptExecutorPlugin.class);
    
    private PluginContext pluginContext;
    private ScriptEngineManager scriptEngineManager;
    
    @Override
    public void initialize(PluginContext context) throws PluginException {
        this.pluginContext = context;
        this.scriptEngineManager = new ScriptEngineManager();
        LOG.info("ScriptExecutorPlugin initialized");
        
        // Log available script engines
        scriptEngineManager.getEngineFactories().forEach(factory -> 
            LOG.debug("Available script engine: {} ({})", 
                factory.getEngineName(), 
                factory.getLanguageName())
        );
    }
    
    @Override
    public void start() throws PluginException {
        LOG.info("ScriptExecutorPlugin started");
    }
    
    @Override
    public void stop() throws PluginException {
        LOG.info("ScriptExecutorPlugin stopped");
    }
    
    @Override
    public String getExecutorType() {
        return "script";
    }
    
    @Override
    public boolean canHandle(NodeExecutionTask task) {
        return "script".equals(task.context().get("__node_type__"));
    }
    
    @Override
    public Uni<NodeExecutionResult> execute(NodeExecutionTask task) {
        String language = (String) task.context().get("language");
        String script = (String) task.context().get("script");
        
        if (language == null || language.isEmpty()) {
            return Uni.createFrom().item(
                SimpleNodeExecutionResult.failure(
                    task.runId(),
                    task.nodeId(),
                    task.attempt(),
                    ErrorInfo.of(new IllegalArgumentException("Language is required for script execution")),
                    task.token()
                )
            );
        }
        
        if (script == null || script.isEmpty()) {
            return Uni.createFrom().item(
                SimpleNodeExecutionResult.failure(
                    task.runId(),
                    task.nodeId(),
                    task.attempt(),
                    ErrorInfo.of(new IllegalArgumentException("Script is required for script execution")),
                    task.token()
                )
            );
        }
        
        LOG.info("Executing {} script for task: {}", language, task.nodeId());
        
        return Uni.createFrom().item(() -> {
            try {
                ScriptEngine engine = scriptEngineManager.getEngineByName(language);
                
                if (engine == null) {
                    throw new IllegalArgumentException(
                        "No script engine found for language: " + language
                    );
                }
                
                // Inject context variables if provided
                @SuppressWarnings("unchecked")
                Map<String, Object> scriptContext = 
                    (Map<String, Object>) task.context().get("context");
                
                if (scriptContext != null) {
                    scriptContext.forEach(engine::put);
                }
                
                // Execute script
                Object result = engine.eval(script);
                
                Map<String, Object> output = new HashMap<>();
                output.put("result", result);
                output.put("language", language);
                
                LOG.info("Script execution completed successfully");
                return SimpleNodeExecutionResult.success(
                    task.runId(),
                    task.nodeId(),
                    task.attempt(),
                    output,
                    task.token(),
                    Duration.ZERO
                );
                
            } catch (Exception e) {
                LOG.error("Script execution failed", e);
                return SimpleNodeExecutionResult.failure(
                    task.runId(),
                    task.nodeId(),
                    task.attempt(),
                    ErrorInfo.of(e),
                    task.token()
                );
            }
        });
    }
    
    @Override
    public int getPriority() {
        return 5; // Medium priority
    }
    
    @Override
    public PluginMetadata getMetadata() {
        return PluginMetadataBuilder.builder()
            .id("script-executor")
            .name("script-executor")
            .version("1.0.0")
            .description("Script execution plugin supporting JavaScript, Groovy, Python")
            .author("Gamelan Team")
            .build();
    }
}
