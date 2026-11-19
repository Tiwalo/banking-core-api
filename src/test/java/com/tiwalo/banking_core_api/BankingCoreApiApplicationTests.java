package com.tiwalo.banking_core_api;

import java.nio.file.Path;
import java.sql.SQLException;
import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"spring.flyway.enabled=true", "tiwalo.transfer.max-amount-minor=1000",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.example.invalid"})
class BankingCoreApiApplicationTests {

	@Test
	void applicationConnectsToPostgreSql(@Autowired DataSource dataSource) throws SQLException {
		try (var connection = dataSource.getConnection()) {
			var database = connection.getMetaData();
			assertThat(database.getDatabaseProductName()).isEqualTo("PostgreSQL");
			assertThat(database.getDatabaseMajorVersion()).isEqualTo(17);
		}
	}

	@Test
	void upgradesDatabaseWithFoundationFlywayHistory(@Autowired DataSource dataSource, @TempDir Path emptyMigrations) {
		String schema = "foundation_upgrade";
		var foundation = Flyway.configure().dataSource(dataSource).schemas(schema)
				.locations("filesystem:" + emptyMigrations).load();
		assertThat(foundation.migrate().migrationsExecuted).isZero();

		var v1 = Flyway.configure().dataSource(dataSource).schemas(schema).target("1").load();
		assertThat(v1.migrate().migrationsExecuted).isOne();
		var jdbc = JdbcClient.create(dataSource);
		jdbc.sql("""
				INSERT INTO foundation_upgrade.account (classification, status, currency)
				VALUES ('TEST_CLEARING_ASSET', 'ACTIVE', 'PHP')
				""").update();
		var v2 = Flyway.configure().dataSource(dataSource).schemas(schema).target("2").load();
		assertThat(v2.migrate().migrationsExecuted).isOne();
		assertThat(jdbc.sql("SELECT balance_minor FROM foundation_upgrade.account_posted_balance")
				.query(Long.class).single()).isZero();
		assertThat(jdbc.sql("SELECT count(*) FROM foundation_upgrade.ledger_journal")
				.query(Long.class).single()).isZero();
		jdbc.sql("INSERT INTO foundation_upgrade.funding_actor (login_name) VALUES (session_user)").update();
		jdbc.sql("""
				WITH holder AS (INSERT INTO foundation_upgrade.customer DEFAULT VALUES RETURNING customer_id)
				INSERT INTO foundation_upgrade.account (customer_id, classification, status, currency, recipient_reference)
				SELECT customer_id, 'DEPOSIT_LIABILITY', 'ACTIVE', 'PHP', 'rcp_' || repeat('1',32) FROM holder
				""").update();
		String funding = """
				SELECT foundation_upgrade.post_simulated_funding(
				    '49342078-618b-457e-8c0c-b62970695936', array_agg(ROW(account_id,
				    CASE classification WHEN 'TEST_CLEARING_ASSET' THEN 'DEBIT' ELSE 'CREDIT' END,
				    'PHP', 41)::foundation_upgrade.ledger_leg)) FROM foundation_upgrade.account
				""";
		var original = jdbc.sql(funding).query(java.util.UUID.class).single();
		var upgrade = Flyway.configure().dataSource(dataSource).schemas(schema).load();
		assertThat(upgrade.migrate().migrationsExecuted).isOne();
		assertThat(jdbc.sql(funding).query(java.util.UUID.class).single()).isEqualTo(original);
		assertThat(jdbc.sql("SELECT balance_minor FROM foundation_upgrade.account_posted_balance")
				.query(Long.class).list()).containsExactly(41L, 41L);
		assertThat(jdbc.sql("SELECT count(*) FROM foundation_upgrade.posting_audit").query(Long.class).single()).isOne();
		upgrade.validate();
		assertThat(upgrade.migrate().migrationsExecuted).isZero();
	}
}
