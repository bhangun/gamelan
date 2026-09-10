package tech.kayys.gamelan.security;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import tech.kayys.gamelan.engine.config.GamelanConfig;
import tech.kayys.gamelan.engine.context.RequestContext;
import tech.kayys.gamelan.engine.tenant.TenantId;

/**
 * Default TenantSecurityContext backed by @RequestScoped RequestContext.
 * Safe for reactive pipelines — no ThreadLocal.
 */
@ApplicationScoped
public class DefaultTenantSecurityContext implements TenantSecurityContext {

    @Inject
    RequestContext requestContext;

    @Inject
    GamelanConfig config;

    @Override
    public TenantId getCurrentTenant() {
        return requestContext.getTenantId()
                .orElseGet(() -> {
                    if (!config.isMultiTenancyEnabled()) {
                        return config.getDefaultTenant();
                    }
                    throw new SecurityException("No tenant context set");
                });
    }

    @Override
    public boolean isTenantSet() {
        return requestContext.hasTenant();
    }

    @Override
    public void setCurrentTenant(TenantId tenantId) {
        requestContext.setTenantId(tenantId);
    }

    @Override
    public void clearTenantContext() {
        requestContext.clearTenantId();
    }

    @Override
    public String getCurrentUser() {
        return requestContext.getUserId().orElse("system");
    }

    @Override
    public Uni<Void> validateAccess(TenantId tenantId) {
        if (!config.isMultiTenancyEnabled()) {
            return Uni.createFrom().voidItem();
        }
        TenantId current = getCurrentTenant();
        if (!current.equals(tenantId)) {
            return Uni.createFrom().failure(new SecurityException("Unauthorized tenant access"));
        }
        return Uni.createFrom().voidItem();
    }

    @Override
    public Uni<Boolean> hasPermission(TenantId tenantId, String permission) {
        return Uni.createFrom().item(true);
    }
}
