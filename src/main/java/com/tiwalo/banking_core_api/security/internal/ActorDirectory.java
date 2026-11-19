package com.tiwalo.banking_core_api.security.internal;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.tiwalo.banking_core_api.customer.CustomerId;
import com.tiwalo.banking_core_api.security.BankingActor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

@Component
class ActorDirectory {

    static final String API_CLIENT = "banking-core-api";
    static final String ACCOUNT_READ = "accounts:read";
    private static final String INVESTIGATE = "operations:investigate";
    private final JdbcClient database;

    ActorDirectory(JdbcClient database) {
        this.database = database;
    }

    OAuth2AuthenticatedPrincipal resolve(Jwt verifiedToken) {
        var roles = clientRoles(verifiedToken);
        return database.sql("""
                SELECT actor_id, customer_id FROM security_actor
                WHERE issuer = :issuer AND subject = :subject
                """)
                .param("issuer", verifiedToken.getClaimAsString("iss")).param("subject", verifiedToken.getSubject())
                .<OAuth2AuthenticatedPrincipal>query((row, index) -> {
                    UUID customer = row.getObject("customer_id", UUID.class);
                    // V1 permits a customer mapping only for a CUSTOMER actor.
                    String allowed = customer == null ? INVESTIGATE : ACCOUNT_READ;
                    return new BankingActor(row.getObject("actor_id", UUID.class),
                            customer == null ? null : new CustomerId(customer),
                            roles.contains(allowed) ? List.of(new SimpleGrantedAuthority(allowed)) : List.of());
                }).optional().orElseGet(() -> new DefaultOAuth2AuthenticatedPrincipal(
                        "unmapped", Map.of("mapped", false), List.of()));
    }

    private static Collection<?> clientRoles(Jwt jwt) {
        if (jwt.getClaim("resource_access") instanceof Map<?, ?> clients
                && clients.get(API_CLIENT) instanceof Map<?, ?> api
                && api.get("roles") instanceof Collection<?> roles) {
            return roles;
        }
        return List.of();
    }
}
