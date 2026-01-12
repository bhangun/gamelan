package tech.kayys.silat.security;

import java.io.IOException;
import java.util.Optional;

import org.eclipse.microprofile.jwt.JsonWebToken;
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
 * Filter to extract tenant from JWT and set in TenantSecurityContext
 */
@Provider
@Priority(Priorities.AUTHENTICATION + 1)
public class JwtTenantFilter implements ContainerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger(JwtTenantFilter.class);
    private static final String TENANT_CLAIM = "tenant_id";

    @Inject
    JsonWebToken jwt;

    @Inject
    TenantSecurityContext tenantSecurityContext;

    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {
        // Only process if we have a valid JWT
        // In tests, @TestSecurity might provide a different principal
        try {
            if (jwt != null && jwt.getName() != null && jwt.getClaimNames() != null) {
                Optional<String> tenantIdStr = jwt.claim(TENANT_CLAIM);

                if (tenantIdStr.isPresent()) {
                    TenantId tenantId = new TenantId(tenantIdStr.get());
                    tenantSecurityContext.setCurrentTenant(tenantId);
                    LOG.debug("Set tenant {} from JWT for user {}", tenantId.value(), jwt.getName());
                } else {
                    LOG.trace("No tenant_id claim in JWT for user {}", jwt.getName());
                }
            }
        } catch (Exception e) {
            // Probably not a JWT principal, skip
            LOG.trace("Failed to extract tenant from JWT: {}", e.getMessage());
        }
    }
}
