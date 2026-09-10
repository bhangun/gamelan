package tech.kayys.gamelan.plugin.impl;

import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;
import tech.kayys.gamelan.engine.context.RequestContext;
import tech.kayys.gamelan.engine.context.SecurityContext;

@ApplicationScoped
public class DefaultSecurityContext implements SecurityContext {

    @Inject
    jakarta.enterprise.inject.Instance<JsonWebToken> jwtInstance;

    @Inject
    RequestContext requestContext;

    private JsonWebToken jwt() {
        return jwtInstance.isResolvable() ? jwtInstance.get() : null;
    }

    @Override
    public String subject() {
        // Prefer RequestContext (already resolved by filter), fall back to JWT
        return requestContext.getUserId()
                .orElseGet(() -> {
                    JsonWebToken jwt = jwt();
                    return jwt != null ? jwt.getSubject() : "anonymous";
                });
    }

    @Override
    public String tenantId() {
        return requestContext.getTenantId()
                .map(t -> t.value())
                .orElseGet(() -> {
                    JsonWebToken jwt = jwt();
                    return jwt != null ? jwt.getClaim("tenant_id") : null;
                });
    }

    @Override
    public Set<String> roles() {
        JsonWebToken jwt = jwt();
        return jwt != null ? jwt.getGroups() : Set.of();
    }

    @Override
    public Set<String> scopes() {
        JsonWebToken jwt = jwt();
        if (jwt == null) return Set.of();
        String scope = jwt.getClaim("scope");
        return scope == null ? Set.of() : Set.of(scope.split(" "));
    }

    @Override
    public boolean hasRole(String role) {
        return roles().contains(role);
    }

    @Override
    public boolean hasScope(String scope) {
        return scopes().contains(scope);
    }

    @Override
    public boolean isServiceAccount() {
        JsonWebToken jwt = jwt();
        return jwt != null && jwt.getClaim("client_id") != null;
    }

    @Override
    public void requireScope(String scope) {
        if (!hasScope(scope)) {
            throw new SecurityException("Missing scope: " + scope);
        }
    }
}
