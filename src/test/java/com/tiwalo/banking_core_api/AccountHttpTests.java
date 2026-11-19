package com.tiwalo.banking_core_api;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@Sql(scripts = "/accounts-fixture.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class AccountHttpTests {

    private static final String ACCOUNTS = "/api/v1/accounts";
    private static final String FIRST = "00000000-0000-4000-8000-000000000001";
    private static final String SECOND_OWNER_ACCOUNT = "00000000-0000-4000-8000-000000000022";
    private static final String READ = "accounts:read";
    private static final TestTokenIssuer ISSUER = new TestTokenIssuer();

    private final int port;
    private final ObjectMapper json;
    private final JdbcClient database;
    private final HttpClient client = HttpClient.newHttpClient();

    @Autowired
    AccountHttpTests(@LocalServerPort int port, ObjectMapper json, JdbcClient database) {
        this.port = port;
        this.json = json;
        this.database = database;
    }

    @DynamicPropertySource
    static void configureDecoder(DynamicPropertyRegistry properties) {
        properties.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> TestTokenIssuer.ISSUER);
        properties.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", ISSUER::jwksUri);
    }

    @AfterAll
    static void stopIssuer() {
        ISSUER.close();
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode success(String path, String token) throws Exception {
        var response = get(path, token);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(s -> assertThat(s).startsWith("application/json"));
        return json.readTree(response.body());
    }

    private void problem(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(s -> assertThat(s).startsWith("application/problem+json"));
        var body = json.readTree(response.body());
        assertThat(body.get("status").asInt()).isEqualTo(status);
        assertThat(body.get("code").asString()).isEqualTo(code);
        assertThat(body.get("type").asString()).startsWith("urn:tiwalo:problem:");
        assertThat(body.get("title").asString()).isNotBlank();
        if (status == 401) {
            assertThat(response.headers().firstValue("WWW-Authenticate")).contains("Bearer");
        }
    }

    @Test
    void readsOnlyOwnedAccountsAndUsesCustomerScopedPagination() throws Exception {
        String token = ISSUER.token("first", READ);
        JsonNode first = success(ACCOUNTS + "/" + FIRST, token);
        assertThat(first.propertyNames()).containsExactlyInAnyOrder("accountId", "recipientReference", "status",
                "currency", "availableBalanceMinor");
        assertThat(first.get("accountId").asString()).isEqualTo(FIRST);
        assertThat(first.get("recipientReference").asString()).matches("rcp_[0-9a-f]{32}");
        assertThat(first.get("availableBalanceMinor").isString()).isTrue();
        assertThat(first.get("availableBalanceMinor").asString()).isEqualTo("0");
        assertThat(first.get("currency").asString()).isEqualTo("PHP");
        assertThat(first.get("status").asString()).isEqualTo("ACTIVE");

        JsonNode page = success(ACCOUNTS, token);
        assertThat(page.get("accounts").size()).isEqualTo(20);
        assertThat(page.at("/accounts/0")).isEqualTo(first);
        String cursor = page.get("nextCursor").asString();
        JsonNode last = success(ACCOUNTS + "?cursor=" + cursor, token);
        assertThat(last.get("accounts").size()).isEqualTo(1);
        assertThat(last.at("/accounts/0/accountId").asString()).endsWith("000021");
        assertThat(last.has("nextCursor")).isFalse();
        String second = ISSUER.token("second", READ);
        problem(get(ACCOUNTS + "?cursor=" + cursor, second), 400, "INVALID_REQUEST");
        JsonNode other = success(ACCOUNTS + "?limit=1&customerId=first", second);
        assertThat(other.get("accounts").size()).isEqualTo(1);
        assertThat(other.at("/accounts/0/accountId").asString()).isEqualTo(SECOND_OWNER_ACCOUNT);
        assertThat(other.at("/accounts/0/status").asString()).isEqualTo("FROZEN");
        assertThat(other.has("nextCursor")).isFalse();
    }

    @Test
    void foreignMissingAndClearingAccountsAreIndistinguishable() throws Exception {
        String token = ISSUER.token("first", READ);
        var foreign = get(ACCOUNTS + "/" + SECOND_OWNER_ACCOUNT, token);
        problem(foreign, 404, "NOT_FOUND");
        for (String suffix : List.of("23", "99")) {
            var hidden = get(ACCOUNTS + "/00000000-0000-4000-8000-0000000000" + suffix, token);
            problem(hidden, 404, "NOT_FOUND");
            assertThat(hidden.body()).isEqualTo(foreign.body());
        }
        for (String suffix : List.of("/rcp_0123456789abcdef0123456789abcdef", "/1-1-1-1-1",
                "?limit=0", "?limit=", "?limit=101", "?limit=not-a-number", "?cursor=bad", "?cursor=" + FIRST)) {
            problem(get(ACCOUNTS + suffix, token), 400, "INVALID_REQUEST");
        }
    }

    @Test
    void accessRequiresBothApiClientRoleAndMappedCustomer() throws Exception {
        for (String subject : List.of("operator", "unmapped", "other-issuer-only")) {
            problem(get(ACCOUNTS, ISSUER.token(subject, READ)), 403, "FORBIDDEN");
        }
        problem(get(ACCOUNTS, ISSUER.token("first", "operations:investigate")), 403, "FORBIDDEN");
        var wrongClient = ISSUER.claims("first", READ)
                .claim("resource_access", Map.of("another-client", Map.of("roles", List.of(READ))))
                .claim("scope", READ);
        problem(get(ACCOUNTS, ISSUER.sign(wrongClient, ISSUER.key)), 403, "FORBIDDEN");
        problem(get("/api/v1/transfers", ISSUER.token("first", READ)), 403, "FORBIDDEN");
    }

    @Test
    void decoderRejectsInvalidCredentialsBeforeAccountLookup() throws Exception {
        problem(get(ACCOUNTS, null), 401, "UNAUTHORIZED");
        for (var claims : List.of(
                ISSUER.claims("first", READ).issuer("https://other.example.invalid"),
                ISSUER.claims("first", READ).audience("another-api"),
                ISSUER.claims("first", READ).expirationTime(Date.from(Instant.now().minusSeconds(300))),
                ISSUER.claims("first", READ).expirationTime(null),
                ISSUER.claims("first", READ).notBeforeTime(Date.from(Instant.now().plusSeconds(300))),
                ISSUER.claims("first", READ).claim("typ", "ID"),
                ISSUER.claims("", READ))) {
            problem(get(ACCOUNTS, ISSUER.sign(claims, ISSUER.key)), 401, "UNAUTHORIZED");
        }
        var differentKey = new RSAKeyGenerator(2048).generate();
        problem(get(ACCOUNTS, ISSUER.sign(ISSUER.claims("first", READ), differentKey)), 401, "UNAUTHORIZED");
    }

    @Test
    void databaseRejectsNonzeroOpeningBalancesAndInvalidOwnership() {
        for (String change : List.of("opening_balance_minor = 1", "opening_balance_minor = -1",
                "customer_id = NULL", "currency = 'USD'", "status = 'UNKNOWN'", "classification = 'TEST_CLEARING_ASSET'")) {
            assertThatThrownBy(() -> database.sql("UPDATE account SET " + change + " WHERE account_id = :id")
                    .param("id", java.util.UUID.fromString(FIRST)).update()).isInstanceOf(DataIntegrityViolationException.class);
        }
    }
}
