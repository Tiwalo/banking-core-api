package com.tiwalo.banking_core_api;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.flyway.enabled=true", "tiwalo.transfer.max-amount-minor=1000"})
class InternalTransferHttpTests {
    @Container
    static final PostgreSQLContainer postgres = new TestcontainersConfiguration().postgresContainer();
    private static final TestTokenIssuer ISSUER = new TestTokenIssuer();
    private static final String PASSWORD = UUID.randomUUID().toString();
    private static final String CREATE = "transfers:create";
    private static final String READ = "transfers:read";
    private static final String PATH = "/api/v1/transfers";
    private static final String KEY = "payment-1";
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    private final String base;
    private final ObjectMapper json;
    private final JdbcClient admin = JdbcClient.create(ownerDataSource());
    private Holder alice;
    private Holder bob;

    @Autowired
    InternalTransferHttpTests(@LocalServerPort int port, ObjectMapper json) {
        this.base = "http://127.0.0.1:" + port;
        this.json = json;
    }

    static DriverManagerDataSource ownerDataSource() {
        return new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        JdbcClient.create(ownerDataSource()).sql("CREATE ROLE transfer_api LOGIN PASSWORD '" + PASSWORD
                + "' IN ROLE tiwalo_runtime").update();
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "transfer_api");
        registry.add("spring.datasource.password", () -> PASSWORD);
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> TestTokenIssuer.ISSUER);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", ISSUER::jwksUri);
    }

    @AfterAll
    static void closeIssuer() {
        ISSUER.close();
    }

    record Holder(UUID actor, UUID customer, UUID account, String address, String subject) {}

    private Holder open() {
        UUID customer = admin.sql("INSERT INTO customer DEFAULT VALUES RETURNING customer_id").query(UUID.class).single();
        String subject = UUID.randomUUID().toString();
        UUID actor = admin.sql("""
                INSERT INTO security_actor (issuer, subject, actor_kind, customer_id)
                VALUES (?, ?, 'CUSTOMER', ?) RETURNING actor_id
                """).param(TestTokenIssuer.ISSUER).param(subject).param(customer).query(UUID.class).single();
        String address = "rcp_" + UUID.randomUUID().toString().replace("-", "");
        UUID account = admin.sql("""
                INSERT INTO account (classification, customer_id, recipient_reference, status, currency)
                VALUES ('DEPOSIT_LIABILITY', ?, ?, 'ACTIVE', 'PHP') RETURNING account_id
                """).param(customer).param(address).query(UUID.class).single();
        return new Holder(actor, customer, account, address, subject);
    }

    @BeforeEach
    void openAccounts() {
        admin.sql("INSERT INTO funding_actor (login_name) VALUES (session_user) ON CONFLICT (login_name) DO NOTHING").update();
        alice = open();
        bob = open();
    }

    private void fund(Holder holder, long amount) {
        UUID clearing = admin.sql("""
                INSERT INTO account (classification, status, currency) VALUES ('TEST_CLEARING_ASSET', 'ACTIVE', 'PHP')
                RETURNING account_id
                """).query(UUID.class).single();
        admin.sql("""
                SELECT post_simulated_funding(gen_random_uuid(), ARRAY[ROW(?, 'DEBIT', 'PHP', ?)::ledger_leg,
                       ROW(?, 'CREDIT', 'PHP', ?)::ledger_leg])
                """).param(clearing).param(amount).param(holder.account()).param(amount).query(UUID.class).single();
    }

    private String body(Holder source, Holder recipient, long amount) {
        return """
                {"sourceAccountId":"%s","recipientReference":"%s","currency":"PHP","amountMinor":"%s"}
                """.formatted(source.account(), recipient.address(), amount).strip();
    }

    private HttpRequest payment(String key, String body, String token) {
        var request = HttpRequest.newBuilder(URI.create(base + PATH)).header("Content-Type", "application/json");
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (key != null) request.header("Idempotency-Key", key);
        return request.POST(HttpRequest.BodyPublishers.ofString(body)).build();
    }

    private HttpRequest payment(Holder source, Holder recipient, long amount, String key) throws Exception {
        return payment(key, body(source, recipient, amount), ISSUER.token(source.subject(), CREATE));
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> receipt(String path, Holder holder, String role) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(base + path))
                .header("Authorization", "Bearer " + ISSUER.token(holder.subject(), role)).GET().build());
    }

    private void problem(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/problem+json");
        var body = json.readTree(response.body());
        assertThat(body.get("code").asString()).isEqualTo(code);
        assertThat(body.get("status").asInt()).isEqualTo(status);
        assertThat(body.get("type").asString()).startsWith("urn:tiwalo:problem:");
        assertThat(body.get("title").asString()).isNotBlank();
    }

    private long scalar(String sql, UUID id) {
        return admin.sql(sql).param(id).query(Long.class).single();
    }

    private void balances(long first, long second) {
        assertThat(scalar("SELECT balance_minor FROM ledger_balance WHERE account_id = ?", alice.account())).isEqualTo(first);
        assertThat(scalar("SELECT balance_minor FROM ledger_balance WHERE account_id = ?", bob.account())).isEqualTo(second);
    }

    private void history(Holder actor, int posted, int declined) {
        assertThat(scalar("SELECT count(*) FROM transfer_idempotency WHERE actor_id = ?", actor.actor())).isEqualTo(posted + declined);
        assertThat(scalar("SELECT count(*) FROM internal_transfer WHERE outcome = 'POSTED' AND actor_id = ?", actor.actor())).isEqualTo(posted);
        assertThat(scalar("SELECT count(*) FROM internal_transfer WHERE outcome <> 'POSTED' AND actor_id = ?", actor.actor())).isEqualTo(declined);
        assertThat(scalar("SELECT count(*) FROM ledger_journal WHERE customer_actor_id = ?", actor.actor())).isEqualTo(posted);
        assertThat(scalar("SELECT count(*) FROM ledger_entry JOIN ledger_journal USING (journal_id) WHERE customer_actor_id = ?",
                actor.actor())).isEqualTo(2L * posted);
        assertThat(scalar("SELECT count(*) FROM posting_audit WHERE customer_actor_id = ? AND database_login = 'transfer_api'",
                actor.actor())).isEqualTo(posted);
    }

    @Test
    void lostResponseAfterCommitReplaysOriginalReceiptAndLocation() throws Exception {
        fund(alice, 1000);
        HttpRequest original = payment(alice, bob, 1000, KEY);
        // Drop the entire downstream response only after the real server has committed and returned 201.
        var committed = new CompletableFuture<HttpResponse<String>>();
        var relay = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        relay.createContext(PATH, exchange -> {
            try (exchange) {
                String payload = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String token = exchange.getRequestHeaders().getFirst("Authorization").substring("Bearer ".length());
                committed.complete(send(payment(exchange.getRequestHeaders().getFirst("Idempotency-Key"), payload, token)));
            } catch (Exception ex) {
                committed.completeExceptionally(ex);
            }
        });
        relay.start();
        try {
            var viaRelay = HttpRequest.newBuilder(original, (name, value) -> true)
                    .uri(URI.create("http://127.0.0.1:" + relay.getAddress().getPort() + PATH)).build();
            assertThatThrownBy(() -> send(viaRelay)).isInstanceOf(IOException.class);
        } finally {
            relay.stop(0);
        }
        var first = committed.get(5, TimeUnit.SECONDS);
        assertThat(first.statusCode()).as(first.body()).isEqualTo(201);
        var result = json.readTree(first.body());
        assertThat(result.propertyNames()).containsExactlyInAnyOrder("transferId","sourceAccountId","recipientReference",
                "currency","amountMinor","status","postedAt");
        assertThat(result.get("amountMinor").asString()).isEqualTo("1000");
        assertThat(result.get("sourceAccountId").asString()).isEqualTo(alice.account().toString());
        assertThat(result.get("recipientReference").asString()).isEqualTo(bob.address());
        assertThat(result.get("status").asString()).isEqualTo("POSTED");
        assertThat(result.get("currency").asString()).isEqualTo("PHP");
        Instant.parse(result.get("postedAt").asString());
        UUID id = UUID.fromString(result.get("transferId").asString());
        String path = PATH + "/" + id;
        assertThat(first.headers().firstValue("Location")).contains(path);
        String reordered = """
                { "currency":"PHP", "amountMinor":"1000", "recipientReference":"%s", "sourceAccountId":"%s" }
                """.formatted(bob.address(), alice.account().toString().toUpperCase(java.util.Locale.ROOT));
        var replay = send(payment(KEY, reordered, ISSUER.token(alice.subject(), CREATE)));
        assertThat(replay.statusCode()).isEqualTo(201);
        assertThat(replay.body()).isEqualTo(first.body());
        assertThat(replay.headers().firstValue("Location")).contains(path);
        var read = receipt(path, alice, READ);
        assertThat(read.statusCode()).isEqualTo(200);
        assertThat(read.body()).isEqualTo(first.body());
        problem(receipt(path, bob, READ), 404, "NOT_FOUND");
        problem(receipt(path, alice, CREATE), 403, "FORBIDDEN");
        assertThat(admin.sql("""
                SELECT e.account_id FROM internal_transfer t JOIN ledger_journal j ON j.journal_id = t.journal_id
                JOIN ledger_entry e ON e.journal_id = j.journal_id JOIN posting_audit a ON a.journal_id = j.journal_id
                WHERE t.transfer_id = ? AND j.reference = t.transfer_id AND a.reference = t.transfer_id
                  AND e.currency = t.currency AND e.amount_minor = t.amount_minor ORDER BY e.direction DESC
                """).param(id).query(UUID.class).list()).containsExactly(alice.account(), bob.account());
        balances(0,1000);
        history(alice,1,0);
    }

    @Test
    void businessDeclinesPersistAcrossFundingAndStatusChanges() throws Exception {
        var request = payment(alice,bob,10,KEY);
        var decline = send(request);
        problem(decline,422,"INSUFFICIENT_FUNDS");
        var declinedId = admin.sql("SELECT transfer_id FROM internal_transfer WHERE actor_id = ?")
                .param(alice.actor()).query(UUID.class).single();
        problem(receipt(PATH + "/" + declinedId, alice, READ), 404, "NOT_FOUND");
        fund(alice,1001);
        assertThat(send(request).body()).isEqualTo(decline.body());
        problem(send(payment(alice, bob, 11, KEY)), 409, "IDEMPOTENCY_CONFLICT");
        problem(send(payment(alice,bob,1001,"cap")),422,"TRANSFER_LIMIT_EXCEEDED");
        problem(send(payment(alice,alice,1,"same")),422,"SAME_ACCOUNT");
        var unknown = body(alice,bob,1).replace(bob.address(),"unknown");
        problem(send(payment("missing",unknown,ISSUER.token(alice.subject(),CREATE))),422,"ACCOUNT_UNAVAILABLE");
        for (var status : List.of("FROZEN","CLOSED")) {
            for (var holder : List.of(alice,bob)) {
                admin.sql("UPDATE account SET status = ? WHERE account_id = ?").param(status).param(holder.account()).update();
                var unavailable = payment(alice,bob,1,status + holder.subject());
                var refused = send(unavailable);
                problem(refused,422,"ACCOUNT_UNAVAILABLE");
                admin.sql("UPDATE account SET status = 'ACTIVE' WHERE account_id = ?").param(holder.account()).update();
                assertThat(send(unavailable).body()).isEqualTo(refused.body());
            }
        }
        balances(1001,0);
        history(alice,0,8);
    }

    @Test
    void recipientOverflowIsAReplayableDecline() throws Exception {
        fund(alice,1);
        fund(bob,Long.MAX_VALUE);
        var request = payment(alice,bob,1,KEY);
        problem(send(request),422,"ACCOUNT_UNAVAILABLE");
        assertThat(send(payment(bob,alice,1,"other")).statusCode()).isEqualTo(201);
        problem(send(request),422,"ACCOUNT_UNAVAILABLE");
        history(alice,0,1);
        balances(2,Long.MAX_VALUE - 1);
    }

    @Test
    void validLongMaximumAndOpaqueRecipientBytesHaveStableOutcomes() throws Exception {
        problem(send(payment(alice, bob, Long.MAX_VALUE, KEY)), 422, "TRANSFER_LIMIT_EXCEEDED");
        String token = ISSUER.token(alice.subject(), CREATE);
        String opaque = body(alice, bob, 1).replace(bob.address(), "not-issued\\u0000");
        problem(send(payment("Opaque", opaque, token)), 422, "ACCOUNT_UNAVAILABLE");
        problem(send(payment("Opaque", opaque.replace("not-issued", "changed"), token)), 409, "IDEMPOTENCY_CONFLICT");
        problem(send(payment("opaque", opaque, token)), 422, "ACCOUNT_UNAVAILABLE");
        history(alice, 0, 3);
    }

    @Test
    void apiLoginCanOnlyWriteThroughTheCheckedTransferCommand() {
        var api = JdbcClient.create(new DriverManagerDataSource(postgres.getJdbcUrl(), "transfer_api", PASSWORD));
        for (String table : List.of("transfer_idempotency", "internal_transfer")) {
            for (String sql : List.of("INSERT INTO " + table + " DEFAULT VALUES", "DELETE FROM " + table,
                    "UPDATE " + table + " SET transfer_id = transfer_id", "TRUNCATE " + table + " CASCADE",
                    "SELECT * FROM " + table)) {
                assertThatThrownBy(() -> api.sql(sql).query().listOfRows()).rootCause()
                        .isInstanceOfSatisfying(SQLException.class, ex -> assertThat(ex.getSQLState()).isEqualTo("42501"));
            }
        }
        assertThatThrownBy(() -> api.sql("SELECT ledger_post_transfer(?, gen_random_uuid(), ?, ?, 10)")
                .param(alice.actor()).param(alice.account()).param(bob.account()).query().listOfRows()).rootCause()
                .isInstanceOfSatisfying(SQLException.class, ex -> assertThat(ex.getSQLState()).isEqualTo("42501"));
        balances(0, 0);
        history(alice, 0, 0);
    }

    @Test
    void rejectsInvalidRequestsBeforeClaimingAKey() throws Exception {
        String token = ISSUER.token(alice.subject(), CREATE);
        String valid = body(alice,bob,1);
        for (String amount : List.of("0","-1","01","1.0","1e2","+1","9223372036854775808")) {
            problem(send(payment(KEY,valid.replace("\"1\"", "\"" + amount + "\""),token)),400,"INVALID_REQUEST");
        }
        for (String invalid : List.of(valid.replace("\"1\"", "1"),valid.replace("PHP","USD"),
                valid.replace(alice.account().toString(),"1-1-1-1-1"),valid.replace(bob.address(),"x".repeat(129)),
                valid.replace(bob.address(),""),valid.replace("amountMinor","other"),valid + "{}","{",
                valid.replace("}",",\"actorId\":\"spoofed\"}"),valid.replace("}",",\"amountMinor\":\"2\"}"))) {
            problem(send(payment(KEY,invalid,token)),400,"INVALID_REQUEST");
        }
        for (String key : new String[]{null,"","a,b","a".repeat(129)}) {
            problem(send(payment(key,valid,token)),400,"INVALID_REQUEST");
        }
        problem(receipt(PATH + "/1-1-1-1-1",alice,READ),400,"INVALID_REQUEST");
        history(alice,0,0);
    }

    @Test
    void rolesAndCurrentOwnershipAreCheckedBeforeReplayOrLookup() throws Exception {
        fund(alice,3);
        String body = body(alice,bob,1);
        String token = ISSUER.token(alice.subject(),CREATE);
        var request = payment(KEY,body,token);
        var success = send(request);
        assertThat(success.statusCode()).isEqualTo(201);
        String path = success.headers().firstValue("Location").orElseThrow();
        problem(send(payment(KEY,body,null)),401,"UNAUTHORIZED");
        problem(send(payment(KEY,body,ISSUER.token(alice.subject(),"accounts:read"))),403,"FORBIDDEN");
        problem(send(payment(KEY,body,ISSUER.token("unmapped",CREATE))),403,"FORBIDDEN");
        admin.sql("INSERT INTO security_actor (issuer,subject,actor_kind) VALUES (?, 'ops', 'OPERATOR')")
                .param(TestTokenIssuer.ISSUER).update();
        problem(send(payment(KEY,body,ISSUER.token("ops",CREATE))),403,"FORBIDDEN");
        problem(send(payment(KEY,body,ISSUER.token(bob.subject(),CREATE))),404,"NOT_FOUND");
        problem(send(payment(KEY,body(bob,alice,1),token)),404,"NOT_FOUND");
        problem(send(payment(KEY,body.replace(alice.account().toString(),UUID.randomUUID().toString()),token)),404,"NOT_FOUND");
        admin.sql("UPDATE account SET customer_id = ? WHERE account_id = ?").param(bob.customer()).param(alice.account()).update();
        problem(send(request),404,"NOT_FOUND");
        problem(receipt(path,alice,READ),404,"NOT_FOUND");
        problem(receipt(path,bob,READ),404,"NOT_FOUND");
        history(alice,1,0);
        history(bob,0,0);
    }

    // The gate is a real independent transaction. Release it only after PostgreSQL
    // reports both application connections blocked inside the transfer command.
    private void waitForBlocked(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (admin.sql("""
                SELECT count(*) FROM pg_stat_activity WHERE usename = 'transfer_api'
                  AND query LIKE '%submit_internal_transfer%' AND cardinality(pg_blocking_pids(pid)) > 0
                """).query(Integer.class).single() < expected) {
            assertThat(System.nanoTime()).as("concurrent transfer sessions reached their locks").isLessThan(deadline);
            Thread.sleep(15);
        }
    }

    private Connection lock(UUID account) throws SQLException {
        Connection gate = ownerDataSource().getConnection();
        gate.setAutoCommit(false);
        try (var statement = gate.prepareStatement("SELECT FROM account WHERE account_id = ? FOR UPDATE")) {
            statement.setObject(1, account);
            try (var result = statement.executeQuery()) { assertThat(result.next()).isTrue(); }
        }
        return gate;
    }

    private List<HttpResponse<String>> compete(HttpRequest first, HttpRequest second, UUID blocked,
                                                UUID expectedHeld) throws Exception {
        try (var gate = lock(blocked)) {
            var one = CLIENT.sendAsync(first,HttpResponse.BodyHandlers.ofString());
            waitForBlocked(1);
            if (expectedHeld != null) {
                assertThatThrownBy(() -> admin.sql("SELECT account_id FROM account WHERE account_id = ? FOR UPDATE NOWAIT")
                        .param(expectedHeld).query(UUID.class).single()).rootCause().isInstanceOfSatisfying(SQLException.class,
                                ex -> assertThat(ex.getSQLState()).isEqualTo("55P03"));
            }
            var two = CLIENT.sendAsync(second,HttpResponse.BodyHandlers.ofString());
            waitForBlocked(2);
            gate.commit();
            return List.of(one.get(10,TimeUnit.SECONDS),two.get(10,TimeUnit.SECONDS));
        }
    }

    @Test
    void concurrentDebitsCannotOverspend() throws Exception {
        fund(alice,10);
        var responses = compete(payment(alice,bob,7,KEY),payment(alice,bob,7,"another"),alice.account(),null);
        assertThat(responses).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(201,422);
        problem(responses.stream().filter(r -> r.statusCode() == 422).findFirst().orElseThrow(),422,"INSUFFICIENT_FUNDS");
        balances(3,7);
        history(alice,1,1);
    }

    @Test
    void oppositeDirectionsLockLowerIdFirstAndIsolateActorsWithIdenticalKeys() throws Exception {
        fund(alice,10);
        fund(bob,10);
        // PostgreSQL UUID order compares unsigned bytes, equivalently canonical hex text.
        boolean aliceFirst = alice.account().toString().compareTo(bob.account().toString()) < 0;
        Holder low = aliceFirst ? alice : bob;
        Holder high = aliceFirst ? bob : alice;
        var responses = compete(payment(high,low,3,KEY),payment(low,high,3,KEY),high.account(),low.account());
        assertThat(responses).extracting(HttpResponse::statusCode).containsExactly(201,201);
        assertThat(responses.getFirst().body()).isNotEqualTo(responses.getLast().body());
        balances(10,10);
        history(alice,1,0);
        history(bob,1,0);
    }

    @Test
    void identicalAndConflictingConcurrentKeysEachPostOnlyOnce() throws Exception {
        fund(alice,10);
        var request = payment(alice,bob,2,KEY);
        var identical = compete(request,request,alice.account(),null);
        assertThat(identical).extracting(HttpResponse::statusCode).containsExactly(201,201);
        assertThat(identical.getFirst().body()).isEqualTo(identical.getLast().body());
        var conflict = compete(payment(alice,bob,3,"next"),payment(alice,bob,4,"next"),alice.account(),null);
        assertThat(conflict.getFirst().statusCode()).isEqualTo(201);
        problem(conflict.getLast(),409,"IDEMPOTENCY_CONFLICT");
        balances(5,5);
        history(alice,2,0);
    }

    @Test
    void ownershipChangedWhileWaitingIsRecheckedBeforeAnyOutcomeCommits() throws Exception {
        fund(alice, 2);
        var request = payment(alice, bob, 2, KEY);
        try (var gate = lock(alice.account())) {
            var pending = CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString());
            waitForBlocked(1);
            try (var change = gate.prepareStatement("UPDATE account SET customer_id = ? WHERE account_id = ?")) {
                change.setObject(1, bob.customer());
                change.setObject(2, alice.account());
                change.executeUpdate();
            }
            gate.commit();
            problem(pending.get(10, TimeUnit.SECONDS), 404, "NOT_FOUND");
        }
        history(alice, 0, 0);
        balances(2, 0);
        admin.sql("UPDATE account SET customer_id = ? WHERE account_id = ?")
                .param(alice.customer()).param(alice.account()).update();
        assertThat(send(request).statusCode()).isEqualTo(201);
        history(alice, 1, 0);
        balances(0, 2);
    }

    @Test
    void boundedWaitReleasesKeyAndAllowsSameIntentToRetry() throws Exception {
        fund(alice,1);
        var request = payment(alice,bob,1,KEY);
        try (var gate = lock(alice.account())) {
            problem(send(request),503,"TRANSFER_BUSY");
            balances(1,0);
            history(alice,0,0);
            gate.rollback();
        }
        assertThat(send(request).statusCode()).isEqualTo(201);
        balances(0,1);
        history(alice,1,0);
    }

    @Test
    void failureAtCommitRollsBackEveryTableAndReleasesKey() throws Exception {
        fund(alice,10);
        var request = payment(alice,bob,7,KEY);
        admin.sql("""
                CREATE FUNCTION test_fail_transfer_commit() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'test commit failure'; END $$
                """).update();
        admin.sql("""
                CREATE CONSTRAINT TRIGGER test_fail_commit AFTER INSERT ON internal_transfer
                DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION test_fail_transfer_commit()
                """).update();
        try {
            problem(send(request), 500, "INTERNAL_ERROR");
            balances(10,0);
            history(alice,0,0);
        } finally {
            admin.sql("DROP TRIGGER test_fail_commit ON internal_transfer").update();
            admin.sql("DROP FUNCTION test_fail_transfer_commit()").update();
        }
        assertThat(send(request).statusCode()).isEqualTo(201);
        balances(3,7);
        history(alice,1,0);
    }
}
