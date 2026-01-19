package tech.kayys.gamelan.plugin.impl;

import jakarta.enterprise.context.ApplicationScoped;
import tech.kayys.gamelan.engine.extension.ExtensionRegistry;
import tech.kayys.gamelan.engine.node.NodeTypeHandler;
import tech.kayys.gamelan.engine.signal.SignalHandler;
import tech.kayys.gamelan.engine.workflow.WorkflowInterceptor;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class DefaultExtensionRegistry implements ExtensionRegistry {

    private final List<WorkflowInterceptor> interceptors = new ArrayList<>();
    private final Map<String, NodeTypeHandler> nodeHandlers = new ConcurrentHashMap<>();
    private final Map<String, List<SignalHandler>> signalHandlers = new ConcurrentHashMap<>();

    @Override
    public void registerInterceptor(WorkflowInterceptor interceptor) {
        interceptors.add(interceptor);
    }

    @Override
    public void registerNodeType(NodeTypeHandler handler) {
        nodeHandlers.put(handler.nodeType(), handler);
    }

    @Override
    public void registerSignalHandler(SignalHandler handler) {
        signalHandlers
                .computeIfAbsent(handler.signalType(), k -> new ArrayList<>())
                .add(handler);
    }

    @Override
    public Collection<WorkflowInterceptor> interceptors() {
        return Collections.unmodifiableList(interceptors);
    }

    @Override
    public NodeTypeHandler nodeType(String type) {
        return nodeHandlers.get(type);
    }

    @Override
    public Collection<SignalHandler> signalHandlers(String signalType) {
        return signalHandlers.getOrDefault(signalType, List.of());
    }

}
