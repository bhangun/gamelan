package tech.kayys.gamelan.security;

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
import tech.kayys.gamelan.engine.context.RequestContext;
import tech.kayys.gamelan.engine.tenant.TenantId;

@Provider
@Priority(Priorities.AUTHENTICATION + 1)
public class JwtTenantFilter implements ContainerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger(JwtTenantFilter.class);

    @Inject
    JsonWebToken jwt;

    @Inject
    RequestContext requestContext;

    @Override
    public void filter(ContainerRequestContext ctx) throws IOException {
        try {
            if (jwt != null && jwt.getName() != null && jwt.getClaimNames() != null) {
                Optional<String> tid = jwt.claim("tenant_id");
                tid.ifPresent(t -> {
                    requestContext.setTenantId(new TenantId(t));
                    LOG.debug("Tenant {} set from JWT for user {}", t, jwt.getName());
                });
                jwt.claim("sub").map(Object::toString).ifPresent(requestContext::setUserId);
            }
        } catch (Exception e) {
            LOG.trace("Failed to extract tenant from JWT: {}", e.getMessage());
        }
    }
}
