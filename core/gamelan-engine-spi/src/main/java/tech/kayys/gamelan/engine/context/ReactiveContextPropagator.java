package tech.kayys.gamelan.engine.context;

import java.util.function.Supplier;

import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import tech.kayys.gamelan.engine.tenant.TenantId;

/**
 * Propagates RequestContext values across reactive (Mutiny) pipeline boundaries.
 *
 * CDI @RequestScoped beans are tied to the originating thread's request scope.
 * When Mutiny switches threads (e.g. after a DB call), the scope may not be
 * available. Use this to capture and restore context explicitly.
 *
 * Usage:
 *   return contextPropagator.withContext(() ->
 *       someRepository.find(id)
 *           .flatMap(item -> anotherService.process(item))
 *   );
 */
@ApplicationScoped
public class ReactiveContextPropagator {

    @Inject
    RequestContext requestContext;

    /**
     * Captures current RequestContext values and ensures they are available
     * throughout the Uni pipeline, even across thread hops.
     */
    public <T> Uni<T> withContext(Supplier<Uni<T>> pipeline) {
        // Capture values eagerly on the calling thread (where CDI scope is active)
        final String requestId = requestContext.getRequestId();
        final TenantId tenantId = requestContext.getTenantId().orElse(null);
        final String userId = requestContext.getUserId().orElse(null);

        return Uni.createFrom().deferred(() -> {
            // Restore on whatever thread Mutiny picks up
            requestContext.setRequestId(requestId);
            if (tenantId != null) requestContext.setTenantId(tenantId);
            if (userId != null) requestContext.setUserId(userId);
            return pipeline.get();
        }).runSubscriptionOn(Infrastructure.getDefaultExecutor());
    }
}
