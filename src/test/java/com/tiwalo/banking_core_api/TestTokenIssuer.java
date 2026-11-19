package com.tiwalo.banking_core_api;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;

/** A fresh signing key and HTTP JWKS endpoint exercise the production decoder. */
final class TestTokenIssuer implements AutoCloseable {

    static final String ISSUER = "https://id.example.invalid/realms/tiwalo";
    static final String AUDIENCE = "banking-core-api";
    final RSAKey key;
    private final HttpServer server;

    TestTokenIssuer() {
        try {
            key = new RSAKeyGenerator(2048).keyID("integration-test").generate();
            byte[] jwks = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/jwks", exchange -> {
                try (exchange) {
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, jwks.length);
                    exchange.getResponseBody().write(jwks);
                }
            });
            server.start();
        } catch (JOSEException | IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    String jwksUri() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/jwks";
    }

    JWTClaimsSet.Builder claims(String subject, String role) {
        return new JWTClaimsSet.Builder().issuer(ISSUER).subject(subject).audience(AUDIENCE)
                .expirationTime(Date.from(Instant.now().plusSeconds(300))).claim("typ", "Bearer")
                .claim("resource_access", Map.of(AUDIENCE, Map.of("roles", List.of(role))));
    }

    String sign(JWTClaimsSet.Builder claims, RSAKey signingKey) throws JOSEException {
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims.build());
        jwt.sign(new RSASSASigner(signingKey));
        return jwt.serialize();
    }

    String token(String subject, String role) throws JOSEException {
        return sign(claims(subject, role), key);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
