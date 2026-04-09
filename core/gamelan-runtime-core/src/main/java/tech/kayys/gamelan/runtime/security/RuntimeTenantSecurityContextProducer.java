package tech.kayys.gamelan.runtime.security;

import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import tech.kayys.gamelan.engine.config.GamelanConfig;
import tech.kayys.gamelan.engine.context.RequestContext;
import tech.kayys.gamelan.security.DefaultTenantSecurityContext;
import tech.kayys.gamelan.security.TenantSecurityContext;

/**
 * Produces TenantSecurityContext for the runtime module.
 * Uses DefaultTenantSecurityContext backed by @RequestScoped RequestContext.
 */
@ApplicationScoped
public class RuntimeTenantSecurityContextProducer {

    @Inject
    RequestContext requestContext;

    @Inject
    GamelanConfig config;

    @Produces
    @DefaultBean
    TenantSecurityContext tenantSecurityContext(DefaultTenantSecurityContext impl) {
        return impl;
    }
}
