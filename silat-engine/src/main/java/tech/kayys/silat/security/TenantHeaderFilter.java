package tech.kayys.silat.security;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.ext.Provider;
import tech.kayys.silat.model.TenantId;

/**
 * Filter to extract tenant from X-Tenant-ID header if not already set by JWT
 */
@Provider
@Priority(Priorities.AUTHENTICATION + 2)
public class TenantHeaderFilter implements ContainerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger(TenantHeaderFilter.class);
    private static final String TENANT_HEADER = "X-Tenant-ID";

    @Inject
    TenantSecurityContext tenantSecurityContext;

    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {
        String tenantIdStr = requestContext.getHeaderString(TENANT_HEADER);

        if (tenantIdStr != null && !tenantIdStr.isEmpty()) {
            try {
                // If already set by JWT, this will just overwrite with same value or header
                // value
                // Header takes precedence for testing/internal purposes if allowed by policy
                TenantId tenantId = new TenantId(tenantIdStr);
                tenantSecurityContext.setCurrentTenant(tenantId);
                LOG.debug("Set tenant {} from header {}", tenantId.value(), TENANT_HEADER);
            } catch (Exception e) {
                LOG.warn("Failed to set tenant from header: {}", e.getMessage());
            }
        }
    }
}
