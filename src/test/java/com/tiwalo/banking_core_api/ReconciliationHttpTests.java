package com.tiwalo.banking_core_api;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.flyway.enabled=true", "tiwalo.transfer.max-amount-minor=1000"})
@Execution(ExecutionMode.SAME_THREAD)
@ExtendWith(OutputCaptureExtension.class)
class ReconciliationHttpTests {
    @Container
    static final PostgreSQLContainer postgres = new TestcontainersConfiguration().postgresContainer();
    private static final String PASSWORD = UUID.randomUUID().toString();
    private static final TestTokenIssuer ISSUER = new TestTokenIssuer();
    private static final String ROLE = "operations:investigate";
    private static final String PATH = "/api/v1/operations/reconciliations";
    private static final String SOURCE = "ce31d1d4-fef0-48ff-96f0-ea2740da3aaa";
    private static final String DESTINATION = "1fa4a253-b218-47ca-8f94-3df97b11a34a";
    private static final String CLEARING = "4a30ad95-f7d0-4272-9cc0-5cf79bda9ec5";
    private static final HttpClient CLIENT = HttpClient.newHttpClient();
    private final DriverManagerDataSource adminSource = ownerDataSource();
    private final JdbcClient admin = JdbcClient.create(adminSource);
    private final JdbcClient runtime = JdbcClient.create(new DriverManagerDataSource(postgres.getJdbcUrl(), "report_api", PASSWORD));
    private final String base;
    private final ObjectMapper json;
    private String token;

    @Autowired
    ReconciliationHttpTests(@LocalServerPort int port, ObjectMapper json) {
        this.base = "http://127.0.0.1:" + port;
        this.json = json;
    }

    private static DriverManagerDataSource ownerDataSource() {
        return new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry props) {
        JdbcClient.create(ownerDataSource()).sql("CREATE ROLE report_api LOGIN PASSWORD '" + PASSWORD + "' IN ROLE tiwalo_runtime").update();
        props.add("spring.datasource.url", postgres::getJdbcUrl);
        props.add("spring.datasource.username", () -> "report_api");
        props.add("spring.datasource.password", () -> PASSWORD);
        props.add("spring.flyway.url", postgres::getJdbcUrl);
        props.add("spring.flyway.user", postgres::getUsername);
        props.add("spring.flyway.password", postgres::getPassword);
        props.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> TestTokenIssuer.ISSUER);
        props.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", ISSUER::jwksUri);
    }

    @BeforeEach
    void seed() throws Exception {
        new ResourceDatabasePopulator(new ClassPathResource("reconciliation-fixture.sql")).execute(adminSource);
        token = ISSUER.token("investigator", ROLE);
    }

    @AfterAll
    static void closeIssuer() { ISSUER.close(); }

    private HttpRequest request(String method, String path, String bearer) {
        var builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(25));
        if (bearer != null) builder.header("Authorization", "Bearer " + bearer);
        return builder.method(method, HttpRequest.BodyPublishers.noBody()).build();
    }

    private JsonNode expect(HttpResponse<String> response, int status) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow())
                .startsWith(status >= 400 ? "application/problem+json" : "application/json");
        if (status == 200) {
            assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        }
        return json.readTree(response.body());
    }

    private JsonNode call(String method, String path, String bearer, int status) throws Exception {
        return expect(CLIENT.send(request(method, path, bearer), HttpResponse.BodyHandlers.ofString()), status);
    }

    private record Report(String path, UUID id) {}

    private Report report(HttpResponse<String> response, int count) {
        var body = expect(response, 201);
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("runId", "startedAt", "findingCount");
        assertThat(body.get("findingCount").asLong()).isEqualTo(count);
        java.time.Instant.parse(body.get("startedAt").asString());
        UUID id = UUID.fromString(body.get("runId").asString());
        String path = PATH + "/" + id + "/findings";
        assertThat(response.headers().firstValue("Location")).contains(path);
        return new Report(path, id);
    }

    private Report capture(int count) throws Exception {
        return report(CLIENT.send(request("POST", PATH, token), HttpResponse.BodyHandlers.ofString()), count);
    }

    private List<JsonNode> findings(Report report) throws Exception {
        return List.copyOf(call("GET", report.path(), token, 200).get("findings").values());
    }

    private List<String> financialState() {
        var state = new ArrayList<String>();
        for (String table : List.of("account", "ledger_balance", "ledger_journal", "ledger_entry", "posting_audit",
                "internal_transfer", "transfer_idempotency")) {
            state.add(admin.sql("SELECT coalesce(jsonb_agg(to_jsonb(r) ORDER BY to_jsonb(r)), '[]')::text FROM "
                    + table + " r").query(String.class).single());
        }
        return state;
    }

    @Test
    void cleanReportPreservesAllFinancialRecordsAndAttributesEachAccess() throws Exception {
        var before = financialState();
        var run = capture(0);
        var page = call("GET", run.path(), ISSUER.token("reviewer", ROLE), 200);
        assertThat(page.propertyNames()).containsExactlyInAnyOrder("runId", "findings");
        assertThat(page.get("findings").size()).isZero();
        assertThat(page.get("runId").asString()).isEqualTo(run.id().toString());
        assertThat(admin.sql("""
                SELECT action || '/' || subject || '/' || outcome FROM investigation_access
                JOIN security_actor USING (actor_id) WHERE run_id = ? ORDER BY action
                """).param(run.id()).query(String.class).list()).containsExactly("READ_FINDINGS/reviewer/OK", "RUN/investigator/OK");
        assertThat(financialState()).isEqualTo(before);
    }

    @Test
    void balanceEvidenceUsesAccountSignsAndRemainsRedactedAndStable(CapturedOutput logs) throws Exception {
        admin.sql("UPDATE ledger_balance SET balance_minor = 2 WHERE account_id IN (?, ?)")
                .param(UUID.fromString(SOURCE)).param(UUID.fromString(CLEARING)).update();
        admin.sql("DELETE FROM ledger_balance WHERE account_id = ?").param(UUID.fromString(DESTINATION)).update();
        var before = financialState();
        var first = findings(capture(3));
        assertThat(first).extracting(n -> n.get("computedBalanceMinor").asString()).containsExactlyInAnyOrder("80", "110", "30");
        for (var finding : first) {
            assertThat(finding.get("reference").asString()).matches("[0-9a-f]{64}");
            assertThat(finding.get("currency").asString()).isEqualTo("PHP");
            if (finding.get("code").asString().equals("MISSING_PROJECTION")) {
                assertThat(finding.propertyNames()).containsExactlyInAnyOrder("reference", "code", "currency", "computedBalanceMinor");
                assertThat(finding.get("computedBalanceMinor").asString()).isEqualTo("30");
            } else {
                assertThat(finding.get("code").asString()).isEqualTo("BALANCE_MISMATCH");
                assertThat(finding.get("projectedBalanceMinor").asString()).isEqualTo("2");
            }
        }
        assertThat(findings(capture(3))).isEqualTo(first);
        assertThat(financialState()).isEqualTo(before);
        String raw = "{\"paymentPayload\":\"sensitive-test-marker\",\"accountId\":\"" + SOURCE + "\"}";
        var unexpectedBody = HttpRequest.newBuilder(URI.create(base + PATH)).header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(raw)).build();
        var error = expect(CLIENT.send(unexpectedBody, HttpResponse.BodyHandlers.ofString()), 400);
        String audit = admin.sql("SELECT jsonb_agg(to_jsonb(a))::text FROM investigation_access a").query(String.class).single();
        for (String surface : List.of(first.toString(), audit, error.toString(), logs.getAll())) {
            assertThat(surface).doesNotContain(token, SOURCE, DESTINATION, CLEARING, "synthetic-payment-marker",
                    "rcp_00000000000000000000000000000002", "sensitive-test-marker", raw);
        }
    }

    // Some corrupt links cannot be produced by any granted production operation.
    // Bypass triggers only inside this isolated container/transaction for these fixtures.
    private void corrupt(String... statements) throws SQLException {
        try (var connection = adminSource.getConnection(); var sql = connection.createStatement()) {
            connection.setAutoCommit(false);
            sql.execute("SET LOCAL session_replication_role = replica");
            for (String change : statements) sql.executeUpdate(change);
            connection.commit();
        }
    }

    @Test
    void reportsJournalImbalanceAndBrokenLinksInBothDirections() throws Exception {
        corrupt("DELETE FROM ledger_entry WHERE direction='CREDIT' AND journal_id IN "
                        + "(SELECT journal_id FROM ledger_journal WHERE kind='INTERNAL_TRANSFER')",
                "UPDATE internal_transfer SET journal_id = gen_random_uuid()",
                "UPDATE posting_audit SET reference=gen_random_uuid() WHERE customer_actor_id IS NOT NULL",
                "UPDATE posting_audit SET journal_id=gen_random_uuid() WHERE actor_id IS NOT NULL");
        var before = financialState();
        var rows = findings(capture(7));
        assertThat(rows).extracting(n -> n.get("code").asString()).containsExactlyInAnyOrder("JOURNAL_IMBALANCE",
                "BALANCE_MISMATCH", "TRANSFER_JOURNAL_MISMATCH", "JOURNAL_WITHOUT_TRANSFER", "POSTING_AUDIT_MISMATCH",
                "AUDIT_WITHOUT_JOURNAL", "MISSING_POSTING_AUDIT");
        var imbalance = rows.stream().filter(n -> "JOURNAL_IMBALANCE".equals(n.get("code").asString())).findFirst().orElseThrow();
        assertThat(imbalance.get("debitTotalMinor").asString()).isEqualTo("30");
        assertThat(imbalance.get("creditTotalMinor").asString()).isEqualTo("0");
        assertThat(financialState()).isEqualTo(before);
    }

    @Test
    void detectsAnEmptyJournalAndReportsExactSumsBeyondBigint() throws Exception {
        corrupt("DELETE FROM ledger_entry WHERE journal_id IN (SELECT journal_id FROM ledger_journal WHERE kind='INTERNAL_TRANSFER')",
                "UPDATE ledger_entry SET amount_minor=9223372036854775807 WHERE direction='CREDIT'",
                "INSERT INTO ledger_entry SELECT journal_id, '" + SOURCE + "', 'CREDIT', 'PHP', 11 FROM ledger_journal WHERE kind='INTERNAL_TRANSFER'");
        var results = findings(capture(5));
        assertThat(results).anySatisfy(row -> assertThat(row.path("computedBalanceMinor").asString("")).isEqualTo("9223372036854775818"));
        corrupt("DELETE FROM ledger_entry WHERE journal_id IN (SELECT journal_id FROM ledger_journal WHERE kind='INTERNAL_TRANSFER')");
        assertThat(findings(capture(5))).anySatisfy(row -> {
            assertThat(row.get("code").asString()).isEqualTo("JOURNAL_IMBALANCE");
            assertThat(row.get("debitTotalMinor").asString()).isEqualTo("0");
            assertThat(row.get("creditTotalMinor").asString()).isEqualTo("0");
        });
    }

    @Test
    void pagesAreBoundedAndKeepTheCapturedEvidence() throws Exception {
        admin.sql("INSERT INTO account (classification, status, currency) SELECT 'TEST_CLEARING_ASSET','ACTIVE','PHP' FROM generate_series(1,105)").update();
        admin.sql("UPDATE ledger_balance SET balance_minor = 1 WHERE balance_minor = 0").update();
        var run = capture(105);
        var page = call("GET", run.path() + "?limit=100", token, 200);
        var refs = new ArrayList<>(page.get("findings").values().stream().map(n -> n.get("reference").asString()).toList());
        assertThat(refs).hasSize(100);
        String cursor = page.get("nextCursor").asString();
        assertThat(cursor).isEqualTo(refs.getLast());
        admin.sql("UPDATE ledger_balance SET balance_minor = 0 WHERE balance_minor = 1").update();
        var clean = capture(0);
        var rest = call("GET", run.path() + "?cursor=" + cursor, token, 200);
        assertThat(rest.has("nextCursor")).isFalse();
        rest.get("findings").values().forEach(n -> refs.add(n.get("reference").asString()));
        assertThat(refs).hasSize(105).isSorted().doesNotHaveDuplicates();
        assertThat(admin.sql("""
                SELECT page_limit FROM investigation_access WHERE run_id = ? AND after_reference = ?
                    AND action = 'READ_FINDINGS' AND outcome = 'OK'
                """).param(run.id()).param(cursor).query(Integer.class).single()).isEqualTo(20);
        assertThat(call("GET", clean.path() + "?cursor=" + cursor, token, 400).get("code").asString()).isEqualTo("INVALID_REQUEST");
        for (String query : List.of("?limit=0", "?limit=101", "?cursor=", "?limit=2&limit=3", "?actorId=unknown")) {
            call("GET", run.path() + query, token, 400);
        }
        call("GET", PATH + "/1-1-1-1-1/findings", token, 400);
        call("GET", PATH + "/" + UUID.randomUUID() + "/findings", token, 404);
        assertThat(admin.sql("SELECT outcome FROM investigation_access WHERE outcome <> 'OK'").query(String.class).list())
                .containsExactlyInAnyOrder("INVALID_CURSOR", "NOT_FOUND");
    }

    @Test
    void missingProjectionOnAnUnfundedAccountIsStillAFinding() throws Exception {
        UUID unfunded = admin.sql("""
                INSERT INTO account (classification, status, currency) VALUES ('TEST_CLEARING_ASSET','ACTIVE','PHP')
                RETURNING account_id
                """).query(UUID.class).single();
        admin.sql("DELETE FROM ledger_balance WHERE account_id = ?").param(unfunded).update();
        var issue = findings(capture(1)).getFirst();
        assertThat(issue.get("code").asString()).isEqualTo("MISSING_PROJECTION");
        assertThat(issue.get("computedBalanceMinor").asString()).isEqualTo("0");
        assertThat(issue.has("projectedBalanceMinor")).isFalse();
    }

    @Test
    void onlyMappedOperatorsCanInvestigateAndRuntimeCannotReadPrivateInterfaces() throws Exception {
        var run = capture(0);
        for (String auth : List.of(ISSUER.token("sender", ROLE), ISSUER.token("investigator", "accounts:read"),
                ISSUER.token("unmapped", ROLE))) {
            call("POST", PATH, auth, 403);
            call("GET", run.path(), auth, 403);
        }
        call("POST", PATH, null, 401);
        call("GET", run.path(), null, 401);
        call("DELETE", run.path(), token, 403);
        call("POST", "/api/v1/transfers", ISSUER.token("investigator", "transfers:create"), 403);
        assertThat(admin.sql("SELECT count(*) FROM investigation_access").query(Long.class).single()).isOne();
        assertThatThrownBy(() -> runtime.sql("SELECT * FROM run_reconciliation((SELECT actor_id FROM security_actor WHERE subject='sender'))")
                .query().listOfRows()).rootCause().isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("TW403"));
        for (String relation : List.of("reconciliation_run", "reconciliation_finding", "investigation_access",
                "ledger_balance_discrepancy", "ledger_journal_discrepancy", "transfer_journal_discrepancy", "audit_posting_discrepancy")) {
            assertThatThrownBy(() -> runtime.sql("SELECT * FROM " + relation).query().listOfRows()).rootCause()
                    .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("42501"));
        }
        assertThatThrownBy(() -> runtime.sql("DELETE FROM investigation_access").update()).rootCause()
                .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("42501"));
    }

    @Test
    void auditFailureWithholdsReportAndFindings() throws Exception {
        admin.sql("UPDATE ledger_balance SET balance_minor = 0 WHERE account_id = ?").param(UUID.fromString(SOURCE)).update();
        var existing = capture(1);
        admin.sql("ALTER TABLE investigation_access ADD CONSTRAINT fail_test_audit CHECK (false) NOT VALID").update();
        try {
            assertThat(call("POST", PATH, token, 500).get("code").asString()).isEqualTo("INTERNAL_ERROR");
            var failure = call("GET", existing.path(), token, 500);
            assertThat(failure.toString()).doesNotContain("BALANCE_MISMATCH", SOURCE, "fail_test_audit", token);
            for (String table : List.of("reconciliation_run", "reconciliation_finding", "investigation_access")) {
                assertThat(admin.sql("SELECT count(*) FROM " + table).query(Long.class).single()).isOne();
            }
        } finally {
            admin.sql("ALTER TABLE investigation_access DROP CONSTRAINT fail_test_audit").update();
        }
    }

    @Test
    void oneSnapshotSurvivesConcurrentPostingAndLaterProjectionChanges() throws Exception {
        try (var gate = adminSource.getConnection(); var lock = gate.createStatement()) {
            gate.setAutoCommit(false);
            lock.execute("LOCK TABLE reconciliation_finding IN ACCESS EXCLUSIVE MODE");
            int pid;
            try (var result = lock.executeQuery("SELECT pg_backend_pid()")) { result.next(); pid = result.getInt(1); }
            var pending = CLIENT.sendAsync(request("POST", PATH, token), HttpResponse.BodyHandlers.ofString());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!admin.sql("""
                    SELECT EXISTS (SELECT FROM pg_stat_activity WHERE usename='report_api'
                                   AND ? = ANY(pg_blocking_pids(pid)))
                    """).param(pid).query(Boolean.class).single()) {
                assertThat(System.nanoTime()).as("report has taken its snapshot and reached the test gate").isLessThan(deadline);
                Thread.sleep(20);
            }
            assertThat(runtime.sql("""
                    SELECT result FROM submit_internal_transfer((SELECT actor_id FROM security_actor WHERE subject='sender'),
                        'concurrent', ?, convert_to('rcp_00000000000000000000000000000002','UTF8'),'PHP',10,1000)
                    """).param(UUID.fromString(SOURCE)).query(String.class).single()).isEqualTo("POSTED");
            // Mark the post-commit snapshot so a reader accidentally taking a new snapshot will fail this test.
            admin.sql("UPDATE ledger_balance SET balance_minor = 69 WHERE account_id = ?").param(UUID.fromString(SOURCE)).update();
            gate.commit();
            assertThat(findings(report(pending.get(10, TimeUnit.SECONDS), 0))).isEmpty();
        }
        var newFinding = findings(capture(1)).getFirst();
        assertThat(newFinding.get("computedBalanceMinor").asString()).isEqualTo("70");
        assertThat(newFinding.get("projectedBalanceMinor").asString()).isEqualTo("69");
    }
}
