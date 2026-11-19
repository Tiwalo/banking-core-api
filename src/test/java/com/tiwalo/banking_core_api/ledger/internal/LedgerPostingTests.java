package com.tiwalo.banking_core_api.ledger.internal;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;

import com.tiwalo.banking_core_api.BankingCoreApiApplication;
import com.tiwalo.banking_core_api.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {"spring.flyway.enabled=true", "tiwalo.transfer.max-amount-minor=1000",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.example.invalid"})
@Import(TestcontainersConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LedgerPostingTests {

    private final JdbcClient owner;
    private final DataSource ownerDataSource;
    private final PostgreSQLContainer postgres;
    private JdbcClient runtime;
    private JdbcClient funder;
    private JdbcClient secondFunder;
    private DriverManagerDataSource fundingConnections;
    private SimulatedFunding service;
    private UUID clearing;
    private UUID deposit;
    private UUID reference;

    @Autowired
    LedgerPostingTests(JdbcClient owner, DataSource ownerDataSource, PostgreSQLContainer postgres) {
        this.owner = owner;
        this.ownerDataSource = ownerDataSource;
        this.postgres = postgres;
    }

    @BeforeAll
    void connectAsRestrictedLogins() {
        runtime = JdbcClient.create(login("test_api", "tiwalo_runtime"));
        fundingConnections = login("test_seed", "tiwalo_dev_funder");
        funder = JdbcClient.create(fundingConnections);
        secondFunder = JdbcClient.create(login("second_seed", "tiwalo_dev_funder"));
        owner.sql("INSERT INTO funding_actor (login_name) VALUES ('test_seed'), ('second_seed')").update();
        service = new SimulatedFunding(funder);
    }

    private DriverManagerDataSource login(String name, String membership) {
        String password = UUID.randomUUID().toString();
        owner.sql("CREATE ROLE " + name + " LOGIN PASSWORD '" + password + "' IN ROLE " + membership).update();
        return new DriverManagerDataSource(postgres.getJdbcUrl(), name, password);
    }

    @BeforeEach
    void openAccounts() {
        clearing = owner.sql("""
                INSERT INTO account (classification, status, currency)
                VALUES ('TEST_CLEARING_ASSET', 'ACTIVE', 'PHP') RETURNING account_id
                """).query(UUID.class).single();
        deposit = owner.sql("""
                WITH holder AS (INSERT INTO customer DEFAULT VALUES RETURNING customer_id)
                INSERT INTO account (customer_id, classification, status, currency, recipient_reference)
                SELECT customer_id, 'DEPOSIT_LIABILITY', 'ACTIVE', 'PHP',
                       'rcp_' || replace(gen_random_uuid()::text, '-', '') FROM holder RETURNING account_id
                """).query(UUID.class).single();
        reference = UUID.randomUUID();
    }

    private long balance(UUID id) {
        return runtime.sql("SELECT balance_minor FROM account_posted_balance WHERE account_id = ?")
                .param(id).query(Long.class).single();
    }

    private long count(String table) {
        return owner.sql("SELECT count(*) FROM " + table + " JOIN ledger_journal USING (journal_id)"
                        + " WHERE ledger_journal.reference = ?")
                .param(reference).query(Long.class).single();
    }

    private void assertState(long amount, int journals) {
        assertThat(balance(clearing)).isEqualTo(amount);
        assertThat(balance(deposit)).isEqualTo(amount);
        assertThat(owner.sql("SELECT count(*) FROM ledger_journal WHERE reference = ?")
                .param(reference).query(Long.class).single()).isEqualTo(journals);
        assertThat(count("ledger_entry")).isEqualTo(2L * journals);
        assertThat(count("posting_audit")).isEqualTo(journals);
    }

    private static void rejects(String sqlState, Runnable statement) {
        assertThatThrownBy(statement::run).isInstanceOf(DataAccessException.class)
                .rootCause().isInstanceOfSatisfying(SQLException.class,
                        cause -> assertThat(cause.getSQLState()).isEqualTo(sqlState));
    }

    @Test
    void fundingPostsBalancedLegsAndAnAttributableAudit() {
        UUID journal = service.post(reference, clearing, deposit, 513);
        assertState(513, 1);
        assertThat(owner.sql("SELECT account_id FROM ledger_entry WHERE journal_id = ? ORDER BY direction DESC")
                .param(journal).query(UUID.class).list()).containsExactly(clearing, deposit);
        assertThat(owner.sql("SELECT amount_minor FROM ledger_entry WHERE journal_id = ? AND currency = 'PHP'")
                .param(journal).query(Long.class).list()).containsExactly(513L, 513L);
        assertThat(owner.sql("""
                SELECT database_login FROM posting_audit JOIN funding_actor USING (actor_id)
                WHERE journal_id = ? AND reference = ? AND database_login = login_name
                """)
                .param(journal).param(reference).query(String.class).single()).isEqualTo("test_seed");
    }

    private UUID postLegs(JdbcClient caller, String legs) {
        return caller.sql("SELECT post_simulated_funding(:ref, ARRAY[" + legs + "]::ledger_leg[])")
                .param("ref", reference).param("clearing", clearing).param("deposit", deposit)
                .query(UUID.class).single();
    }

    private static final String DEBIT = "ROW(:clearing, 'DEBIT', 'PHP', 10)::ledger_leg";
    private static final String CREDIT = "ROW(:deposit, 'CREDIT', 'PHP', 10)::ledger_leg";

    @Test
    void nonWebFundingCommandPostsAsRestrictedLoginWithoutAnIssuer() {
        try (var context = new SpringApplicationBuilder(BankingCoreApiApplication.class)
                .profiles("local-funding")
                .initializers(application -> TestPropertyValues.of("spring.datasource.username=test_seed",
                        "spring.datasource.password=" + fundingConnections.getPassword()).applyTo(application))
                .run("--spring.datasource.url=" + postgres.getJdbcUrl(), "--funding.reference=" + reference,
                        "--funding.clearing=" + clearing, "--funding.deposit=" + deposit, "--funding.amount-minor=37")) {
            assertThat(context.getBeansOfType(LocalFundingCommand.class)).hasSize(1);
            assertState(37, 1);
        }
    }

    @Test
    void invalidJournalsLeaveNoHistory() {
        for (String legs : List.of("", DEBIT, DEBIT + "," + DEBIT, CREDIT + "," + CREDIT,
                DEBIT + "," + CREDIT.replace("10", "9"), DEBIT + "," + CREDIT.replace("PHP", "USD"),
                DEBIT + "," + CREDIT.replace(":deposit", ":clearing"),
                DEBIT.replace(":clearing", ":deposit") + "," + CREDIT.replace(":deposit", ":clearing"),
                DEBIT.replace("10", "0") + "," + CREDIT.replace("10", "0"),
                DEBIT.replace("10", "-2") + "," + CREDIT.replace("10", "-2"),
                DEBIT.replace("10", "NULL") + "," + CREDIT.replace("10", "NULL"),
                DEBIT + "," + CREDIT + "," + CREDIT)) {
            rejects("22023", () -> postLegs(funder, legs));
        }
        rejects("22023", () -> service.post(reference, UUID.randomUUID(), deposit, 10));
        for (String status : List.of("FROZEN", "CLOSED")) {
            owner.sql("UPDATE account SET status = ? WHERE account_id = ?").param(status).param(deposit).update();
            rejects("22023", () -> service.post(reference, clearing, deposit, 10));
        }
        assertState(0, 0);
    }

    @Test
    void concurrentReplayReturnsOriginalJournalAndSeparatesFundingActors() throws Exception {
        UUID original;
        try (var threads = Executors.newSingleThreadExecutor(); var first = fundingConnections.getConnection()) {
            first.setAutoCommit(false);
            var transaction = JdbcClient.create(new SingleConnectionDataSource(first, true));
            int pid = transaction.sql("SELECT pg_backend_pid()").query(Integer.class).single();
            original = new SimulatedFunding(transaction).post(reference, clearing, deposit, 10);
            assertState(0, 0);
            var replay = threads.submit(() -> service.post(reference, clearing, deposit, 10));
            // Wait for PostgreSQL to confirm actual contention before allowing the winner to commit.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
            while (!owner.sql("""
                    SELECT EXISTS (SELECT FROM pg_stat_activity WHERE usename = 'test_seed'
                                   AND :winner = ANY(pg_blocking_pids(pid)))
                    """).param("winner", pid).query(Boolean.class).single()) {
                assertThat(System.nanoTime()).as("duplicate request reached the database lock").isLessThan(deadline);
                Thread.sleep(20);
            }
            first.commit();
            assertThat(replay.get(10, TimeUnit.SECONDS)).isEqualTo(original);
        }
        assertState(10, 1);
        assertThat(postLegs(secondFunder, DEBIT + "," + CREDIT)).isNotEqualTo(original);
        owner.sql("UPDATE account SET status = 'FROZEN' WHERE account_id = ?").param(deposit).update();
        assertThat(postLegs(funder, CREDIT + "," + DEBIT)).isEqualTo(original);
        rejects("22023", () -> service.post(reference, clearing, deposit, 11));
        assertState(20, 2);
    }

    @Test
    void contentionTimesOutWithoutPostingAndSameReferenceCanRetry() throws SQLException {
        try (var blocker = ownerDataSource.getConnection()) {
            blocker.setAutoCommit(false);
            try (var lock = blocker.prepareStatement("SELECT account_id FROM account WHERE account_id = ? FOR UPDATE")) {
                lock.setObject(1, clearing);
                try (var locked = lock.executeQuery()) {
                    assertThat(locked.next()).isTrue();
                }
            }
            try {
                rejects("55P03", () -> service.post(reference, clearing, deposit, 31));
                assertState(0, 0);
            } finally {
                blocker.rollback();
            }
        }
        service.post(reference, clearing, deposit, 31);
        assertState(31, 1);
    }

    @Test
    void projectionOverflowRollsBackJournalAndBothBalances() {
        service.post(reference, clearing, deposit, Long.MAX_VALUE);
        UUID first = reference;
        reference = UUID.randomUUID();
        rejects("22003", () -> service.post(reference, clearing, deposit, 1));
        assertState(Long.MAX_VALUE, 0);
        reference = first;
        assertState(Long.MAX_VALUE, 1);
    }

    @Test
    void auditFailureRollsBackEntirePostAndReleasesReference() {
        owner.sql("ALTER TABLE posting_audit ADD CONSTRAINT test_audit_failure CHECK (reference <> '"
                + reference + "') NOT VALID").update();
        try {
            rejects("23514", () -> service.post(reference, clearing, deposit, 77));
            assertState(0, 0);
        } finally {
            owner.sql("ALTER TABLE posting_audit DROP CONSTRAINT test_audit_failure").update();
        }
        service.post(reference, clearing, deposit, 77);
        assertState(77, 1);
    }

    @Test
    void runtimeAndFundingLoginsCannotRewriteAppendOrBypassPosting() {
        UUID journal = service.post(reference, clearing, deposit, 10);
        rejects("42501", () -> postLegs(runtime, DEBIT + "," + CREDIT));
        var unmapped = JdbcClient.create(login("unmapped_seed", "tiwalo_dev_funder"));
        rejects("42501", () -> postLegs(unmapped, DEBIT + "," + CREDIT));
        for (JdbcClient caller : List.of(runtime, funder)) {
            for (String table : List.of("ledger_journal", "ledger_entry", "ledger_balance", "posting_audit")) {
                String key = table.equals("ledger_balance") ? "account_id" : "journal_id";
                for (String change : List.of("INSERT INTO " + table + " DEFAULT VALUES",
                        "UPDATE " + table + " SET " + key + " = " + key, "DELETE FROM " + table,
                        "TRUNCATE " + table + " CASCADE")) {
                    rejects("42501", () -> caller.sql(change).update());
                }
            }
            rejects("42501", () -> caller.sql("UPDATE account SET status = 'CLOSED'").update());
            rejects("42501", () -> caller.sql("UPDATE funding_actor SET login_name = current_user").update());
            rejects("42501", () -> caller.sql("CREATE TABLE public.unauthorized (id int)").update());
        }
        rejects("23514", () -> owner.sql("DELETE FROM ledger_entry WHERE journal_id = ?").param(journal).update());
        rejects("23505", () -> owner.sql("""
                INSERT INTO ledger_entry SELECT * FROM ledger_entry WHERE journal_id = ? AND direction = 'CREDIT'
                """).param(journal).update());
        assertState(10, 1);
    }
}
