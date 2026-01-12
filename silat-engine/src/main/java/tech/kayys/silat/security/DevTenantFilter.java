package tech.kayys.silat.security;

import java.io.IOException;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.quarkus.arc.profile.IfBuildProfile;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.ext.Provider;
import tech.kayys.silat.model.TenantId;

/**
 * Development-only filter to set a default tenant when no tenant context is available.
 * This filter is only active in the 'dev' profile to ensure tenant isolation in production.
 */
@IfBuildProfile("dev")
@Provider
@Priority(Priorities.AUTHENTICATION + 3)
public class DevTenantFilter implements ContainerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger(DevTenantFilter.class);

    @ConfigProperty(name = "silat.tenant.default-id")
    String defaultTenantId;

    @ConfigProperty(name = "silat.tenant.allow-default")
    boolean allowDefault;

    @Inject
    TenantSecurityContext tenantSecurityContext;

    @Override
    public void filter(ContainerRequestContext ctx) throws IOException {
        // Only set default tenant if no tenant is already set AND default is allowed
        if (allowDefault && defaultTenantId != null && !tenantSecurityContext.isTenantSet()) {
            // No tenant context is set, so we can set the default for dev
            TenantId tenantId = new TenantId(defaultTenantId);
            tenantSecurityContext.setCurrentTenant(tenantId);
            LOG.warn("⚠ Using DEFAULT TENANT [{}] via DevTenantFilter (dev only)", defaultTenantId);
        }
    }
}