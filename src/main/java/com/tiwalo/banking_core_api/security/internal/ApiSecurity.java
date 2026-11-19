package com.tiwalo.banking_core_api.security.internal;

import java.io.IOException;
import java.util.List;

import com.tiwalo.banking_core_api.shared.ApiProblem;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class ApiSecurity {

    @Bean
    JwtDecoder jwtDecoder(
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer,
            @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri:"
                    + "${spring.security.oauth2.resourceserver.jwt.issuer-uri}/protocol/openid-connect/certs}") String keys) {
        var decoder = NimbusJwtDecoder.withJwkSetUri(keys).build();
        OAuth2TokenValidator<Jwt> accessToken = jwt -> {
            if (jwt.getAudience() != null && jwt.getAudience().contains(ActorDirectory.API_CLIENT)
                    && jwt.getExpiresAt() != null && jwt.getSubject() != null && !jwt.getSubject().isBlank()
                    && "Bearer".equals(jwt.getClaimAsString("typ"))) {
                return OAuth2TokenValidatorResult.success();
            }
            return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
        };
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer), accessToken));
        return decoder;
    }

    @Bean
    SecurityFilterChain apiFilterChain(HttpSecurity http, ObjectMapper json, ActorDirectory directory) throws Exception {
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> List.of());
        converter.setJwtPrincipalConverter(directory::resolve);

        AuthenticationEntryPoint unauthorized = (request, response, error) -> {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
            writeProblem(response, json, ApiProblem.UNAUTHORIZED);
        };
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(routes -> routes
                        .requestMatchers(HttpMethod.GET, "/api/v1/accounts", "/api/v1/accounts/{accountId}")
                        .hasAuthority(ActorDirectory.ACCOUNT_READ)
                        .requestMatchers(HttpMethod.POST, "/api/v1/transfers").hasAuthority(ActorDirectory.TRANSFER_CREATE)
                        .requestMatchers(HttpMethod.GET, "/api/v1/transfers/{transferId}").hasAuthority(ActorDirectory.TRANSFER_READ)
                        .anyRequest().denyAll())
                .exceptionHandling(errors -> errors.authenticationEntryPoint(unauthorized)
                        .accessDeniedHandler((req, res, ex) -> writeProblem(res, json, ApiProblem.FORBIDDEN)))
                .oauth2ResourceServer(oauth -> oauth.authenticationEntryPoint(unauthorized)
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(converter)))
                .build();
    }

    private static void writeProblem(HttpServletResponse response, ObjectMapper json, ApiProblem problem)
            throws IOException {
        response.setStatus(problem.status());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        json.writeValue(response.getOutputStream(), problem);
    }
}
