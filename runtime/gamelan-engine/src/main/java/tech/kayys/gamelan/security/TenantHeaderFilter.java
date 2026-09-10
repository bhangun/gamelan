package tech.kayys.gamelan.security;

import java.io.IOException;

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
@Priority(Priorities.AUTHENTICATION + 2)
public class TenantHeaderFilter implements ContainerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger(TenantHeaderFilter.class);
    private static final String TENANT_HEADER = "X-Tenant-ID";
    private static final String REQUEST_ID_HEADER = "X-Request-ID";

    @Inject
    RequestContext requestContext;

    @Override
    public void filter(ContainerRequestContext ctx) throws IOException {
        String requestId = ctx.getHeaderString(REQUEST_ID_HEADER);
        if (requestId != null && !requestId.isBlank()) {
            requestContext.setRequestId(requestId);
        }

        String tenantIdStr = ctx.getHeaderString(TENANT_HEADER);
        if (tenantIdStr != null && !tenantIdStr.isBlank()) {
            requestContext.setTenantId(new TenantId(tenantIdStr));
            LOG.debug("Tenant {} set from header", tenantIdStr);
        }
    }
}
