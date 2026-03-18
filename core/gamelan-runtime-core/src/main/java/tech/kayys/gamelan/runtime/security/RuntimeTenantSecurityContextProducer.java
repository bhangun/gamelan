package tech.kayys.gamelan.runtime.security;

import io.quarkus.arc.DefaultBean;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.security.TenantSecurityContext;

@ApplicationScoped
public class RuntimeTenantSecurityContextProducer {

    @Produces
    @DefaultBean
    TenantSecurityContext tenantSecurityContext() {
        return new TenantSecurityContext() {
            private final ThreadLocal<TenantId> current = ThreadLocal.withInitial(() -> new TenantId("community"));

            @Override
            public void setCurrentTenant(TenantId tenantId) {
                current.set(tenantId);
            }

            @Override
            public boolean isTenantSet() {
                return current.get() != null;
            }

            @Override
            public TenantId getCurrentTenant() {
                TenantId tenant = current.get();
                return tenant != null ? tenant : new TenantId("community");
            }

            @Override
            public String getCurrentUser() {
                return "community-user";
            }

            @Override
            public void clearTenantContext() {
                current.remove();
            }

            @Override
            public Uni<Void> validateAccess(TenantId tenantId) {
                return Uni.createFrom().voidItem();
            }

            @Override
            public Uni<Boolean> hasPermission(TenantId tenantId, String permission) {
                return Uni.createFrom().item(true);
            }
        };
    }
}
