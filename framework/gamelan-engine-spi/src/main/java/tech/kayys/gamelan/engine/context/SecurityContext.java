package tech.kayys.gamelan.engine.context;

import java.util.Set;

public interface SecurityContext {

    String subject(); // user / service id

    String tenantId();

    Set<String> roles();

    Set<String> scopes();

    boolean hasRole(String role);

    boolean hasScope(String scope);

    boolean isServiceAccount();

    void requireScope(String scope);
}
