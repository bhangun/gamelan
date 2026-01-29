package tech.kayys.gamelan.sdk.executor.core;

import tech.kayys.gamelan.engine.protocol.CommunicationType;

import java.lang.annotation.*;
import java.lang.annotation.Inherited;

/**
 * ============================================================================
 * GAMELAN EXECUTOR SDK
 * ============================================================================
 * 
 * Framework for building workflow executors that process node tasks.
 * Supports multiple communication strategies (gRPC, Kafka, REST).
 * 
 * Example Usage:
 * ```java
 * @Executor(
 *     executorType = "order-validator",
 *     communicationType = CommunicationType.GRPC
 * )
 * public class OrderValidatorExecutor extends AbstractWorkflowExecutor {
 *     
 *     @Override
 *     public Uni<NodeExecutionResult> execute(NodeExecutionTask task) {
 *         Map<String, Object> context = task.context();
 *         String orderId = (String) context.get("orderId");
 *         
 *         return validateOrder(orderId)
 *             .map(valid -> NodeExecutionResult.success(
 *                 task.runId(),
 *                 task.nodeId(),
 *                 task.attempt(),
 *                 Map.of("valid", valid),
 *                 task.token()
 *             ));
 *     }
 * }
 * ```
 */

// ==================== EXECUTOR ANNOTATION ====================

/**
 * Annotation to mark a class as a workflow executor
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Documented
public @interface Executor {

    /**
     * Unique executor type identifier
     */
    String executorType();

    /**
     * Communication type for receiving tasks
     */
    CommunicationType communicationType() default CommunicationType.GRPC;

    /**
     * Maximum concurrent tasks
     */
    int maxConcurrentTasks() default 10;

    /**
     * Supported node types
     */
    String[] supportedNodeTypes() default {};

    /**
     * Executor version
     */
    String version() default "1.0.0";
}
