package tech.kayys.gamelan.security;

import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.engine.tenant.TenantId;

/**
 * Resolves the current tenant from the active RequestContext.
 * Implementations vary by deployment mode.
 */
public interface TenantSecurityContext {

    TenantId getCurrentTenant();

    String getCurrentUser();

    boolean isTenantSet();

    void setCurrentTenant(TenantId tenantId);

    void clearTenantContext();

    Uni<Void> validateAccess(TenantId tenantId);

    Uni<Boolean> hasPermission(TenantId tenantId, String permission);
}
