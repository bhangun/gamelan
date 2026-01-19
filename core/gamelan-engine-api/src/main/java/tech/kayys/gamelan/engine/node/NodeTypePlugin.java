package tech.kayys.gamelan.engine.node;

import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.engine.plugin.GamelanPlugin;

public interface NodeTypePlugin extends GamelanPlugin {
    String getNodeType();

    Uni<NodeExecutionResult> execute(NodeExecutionTask task);
}
