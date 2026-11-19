package com.tiwalo.banking_core_api.security;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

import com.tiwalo.banking_core_api.customer.CustomerId;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;

public record BankingActor(UUID actorId, CustomerId customerId, Collection<GrantedAuthority> permissions)
        implements OAuth2AuthenticatedPrincipal {

    public CustomerId requireCustomer() {
        if (customerId == null) {
            throw new AccessDeniedException("Customer identity required");
        }
        return customerId;
    }

    @Override public String getName() {
        return actorId.toString();
    }

    @Override public Map<String, Object> getAttributes() {
        return Map.of("actor_id", actorId);
    }

    @Override public Collection<GrantedAuthority> getAuthorities() {
        return permissions;
    }
}
