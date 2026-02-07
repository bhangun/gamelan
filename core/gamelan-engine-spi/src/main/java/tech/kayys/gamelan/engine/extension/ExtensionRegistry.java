package tech.kayys.gamelan.engine.extension;

import java.util.Collection;

import tech.kayys.gamelan.engine.node.NodeTypeHandler;
import tech.kayys.gamelan.engine.signal.SignalHandler;
import tech.kayys.gamelan.engine.workflow.WorkflowInterceptor;

public interface ExtensionRegistry {

    void registerInterceptor(WorkflowInterceptor interceptor);

    void registerNodeType(NodeTypeHandler handler);

    void registerSignalHandler(SignalHandler handler);

    Collection<WorkflowInterceptor> interceptors();

    NodeTypeHandler nodeType(String type);

    Collection<SignalHandler> signalHandlers(String signalType);
}