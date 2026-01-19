package tech.kayys.gamelan.security;

import java.io.IOException;

import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;

/**
 * Filter to clear tenant context after request
 */
@Provider
public class TenantCleanupFilter implements ContainerResponseFilter {

    @Inject
    TenantSecurityContext tenantSecurityContext;

    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext)
            throws IOException {
        tenantSecurityContext.clearTenantContext();
    }
}
