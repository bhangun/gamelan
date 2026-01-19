package tech.kayys.gamelan.plugin.impl;

import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;
import tech.kayys.gamelan.engine.context.SecurityContext;

@ApplicationScoped
public class DefaultSecurityContext implements SecurityContext {

    @Inject
    JsonWebToken jwt;

    @Override
    public String subject() {
        return jwt.getSubject();
    }

    @Override
    public String tenantId() {
        return jwt.getClaim("tenant_id");
    }

    @Override
    public Set<String> roles() {
        return jwt.getGroups();
    }

    @Override
    public Set<String> scopes() {
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
        return jwt.getClaim("client_id") != null;
    }

    @Override
    public void requireScope(String scope) {
        if (!hasScope(scope)) {
            throw new SecurityException("Missing scope: " + scope);
        }
    }
}
