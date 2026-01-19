package tech.kayys.gamelan.test.plugins;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.smallrye.mutiny.Uni;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.client.WebClient;
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
 * Example executor plugin for HTTP requests
 * 
 * Handles tasks of type "http-request" and executes HTTP calls
 * using Vert.x WebClient.
 * 
 * Example task inputs:
 * - url: "https://api.github.com/users/octocat"
 * - method: "GET" (optional, defaults to GET)
 * - headers: {"Authorization": "Bearer token"} (optional)
 * - body: {"key": "value"} (optional, for POST/PUT)
 */
public class HttpExecutorPlugin implements ExecutorPlugin {
    
    private static final Logger LOG = LoggerFactory.getLogger(HttpExecutorPlugin.class);
    
    private PluginContext context;
    private WebClient webClient;
    
    @Override
    public void initialize(PluginContext context) throws PluginException {
        this.context = context;
        this.webClient = WebClient.create(Vertx.vertx());
        LOG.info("HttpExecutorPlugin initialized");
    }
    
    @Override
    public void start() throws PluginException {
        LOG.info("HttpExecutorPlugin started");
    }
    
    @Override
    public void stop() throws PluginException {
        if (webClient != null) {
            webClient.close();
        }
        LOG.info("HttpExecutorPlugin stopped");
    }
    
    @Override
    public String getExecutorType() {
        return "http";
    }
    
    @Override
    public boolean canHandle(NodeExecutionTask task) {
        return "http-request".equals(task.context().get("__node_type__"));
    }
    
    @Override
    public Uni<NodeExecutionResult> execute(NodeExecutionTask task) {
        String url = (String) task.context().get("url");
        String method = (String) task.context().getOrDefault("method", "GET");
        
        if (url == null || url.isEmpty()) {
            return Uni.createFrom().item(
                SimpleNodeExecutionResult.failure(
                    task.runId(),
                    task.nodeId(),
                    task.attempt(),
                    ErrorInfo.of(new IllegalArgumentException("URL is required for HTTP request")),
                    task.token()
                )
            );
        }
        
        LOG.info("Executing HTTP {} request to: {}", method, url);
        
        return Uni.createFrom().emitter(emitter -> {
            webClient.requestAbs(HttpMethod.valueOf(method.toUpperCase()), url)
                .send()
                .onSuccess(response -> {
                    Map<String, Object> result = new HashMap<>();
                    result.put("statusCode", response.statusCode());
                    result.put("statusMessage", response.statusMessage());
                    result.put("body", response.bodyAsString());
                    
                    LOG.info("HTTP request completed with status: {}", response.statusCode());
                    emitter.complete(SimpleNodeExecutionResult.success(
                        task.runId(),
                        task.nodeId(),
                        task.attempt(),
                        result,
                        task.token(),
                        Duration.ZERO
                    ));
                })
                .onFailure(error -> {
                    LOG.error("HTTP request failed", error);
                    emitter.complete(SimpleNodeExecutionResult.failure(
                        task.runId(),
                        task.nodeId(),
                        task.attempt(),
                        ErrorInfo.of(error),
                        task.token()
                    ));
                });
        });
    }
    
    @Override
    public int getPriority() {
        return 10; // Higher priority for HTTP tasks
    }
    
    @Override
    public PluginMetadata getMetadata() {
        return PluginMetadataBuilder.builder()
            .id("http-executor")
            .name("http-executor")
            .version("1.0.0")
            .description("HTTP request executor plugin")
            .author("Gamelan Team")
            .build();
    }
}
