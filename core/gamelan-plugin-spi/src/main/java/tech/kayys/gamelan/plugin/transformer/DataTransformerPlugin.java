package tech.kayys.gamelan.plugin.transformer;

import java.util.Map;

import tech.kayys.gamelan.engine.node.NodeContext;
import tech.kayys.gamelan.engine.plugin.GamelanPlugin;

/**
 * Plugin interface for data transformers
 * 
 * Transformer plugins can transform input/output data for workflow nodes.
 */
public interface DataTransformerPlugin extends GamelanPlugin {

    /**
     * Check if this transformer supports the given node type
     * 
     * @param nodeType the node type
     * @return true if this transformer can handle the node type
     */
    boolean supports(String nodeType);

    /**
     * Transform input data before task execution
     * 
     * @param input the input data
     * @param node  the node definition
     * @return the transformed input data
     */
    Map<String, Object> transformInput(Map<String, Object> input, NodeContext node);

    /**
     * Transform output data after task execution
     * 
     * @param output the output data
     * @param node   the node definition
     * @return the transformed output data
     */
    Map<String, Object> transformOutput(Map<String, Object> output, NodeContext node);

}
