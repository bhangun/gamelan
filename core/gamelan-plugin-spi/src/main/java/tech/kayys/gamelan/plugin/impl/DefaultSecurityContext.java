package tech.kayys.gamelan.plugin.impl;

import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;
import tech.kayys.gamelan.engine.context.SecurityContext;

@ApplicationScoped
public class DefaultSecurityContext implements SecurityContext {

    @Inject
    jakarta.enterprise.inject.Instance<JsonWebToken> jwtInstance;

    private JsonWebToken getJwt() {
        if (jwtInstance.isResolvable()) {
            return jwtInstance.get();
        }
        return new JsonWebToken() {
            @Override
            public String getName() {
                return "anonymous";
            }

            @Override
            public Set<String> getClaimNames() {
                return Set.of();
            }

            @Override
            public <T> T getClaim(String claimName) {
                return null;
            }

            @Override
            public Set<String> getGroups() {
                return Set.of();
            }

            @Override
            public long getExpirationTime() {
                return 0;
            }

            @Override
            public long getIssuedAtTime() {
                return 0;
            }

            @Override
            public String getRawToken() {
                return null;
            }

            @Override
            public String getIssuer() {
                return null;
            }

            @Override
            public Set<String> getAudience() {
                return Set.of();
            }

            @Override
            public String getSubject() {
                return "anonymous";
            }

            @Override
            public String getTokenID() {
                return null;
            }
        };
    }

    @Override
    public String subject() {
        return getJwt().getSubject();
    }

    @Override
    public String tenantId() {
        return getJwt().getClaim("tenant_id");
    }

    @Override
    public Set<String> roles() {
        return getJwt().getGroups();
    }

    @Override
    public Set<String> scopes() {
        String scope = getJwt().getClaim("scope");
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
        return getJwt().getClaim("client_id") != null;
    }

    @Override
    public void requireScope(String scope) {
        if (!hasScope(scope)) {
            throw new SecurityException("Missing scope: " + scope);
        }
    }
}
