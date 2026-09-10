package tech.kayys.gamelan.security;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.quarkus.arc.profile.IfBuildProfile;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.ext.Provider;
import tech.kayys.gamelan.engine.config.GamelanConfig;
import tech.kayys.gamelan.engine.context.RequestContext;

/**
 * Dev-only: sets default tenant when multi-tenancy is disabled and no tenant is set.
 */
@IfBuildProfile("dev")
@Provider
@Priority(Priorities.AUTHENTICATION + 3)
public class DevTenantFilter implements ContainerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger(DevTenantFilter.class);

    @Inject
    RequestContext requestContext;

    @Inject
    GamelanConfig config;

    @Override
    public void filter(ContainerRequestContext ctx) throws IOException {
        if (!config.isMultiTenancyEnabled() && !requestContext.hasTenant()) {
            requestContext.setTenantId(config.getDefaultTenant());
            LOG.warn("⚠ Using default tenant [{}] (dev/standalone mode)", config.getDefaultTenant().value());
        }
    }
}
