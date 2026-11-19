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
@SpringBootTest(properties = {"spring.flyway.enabled=true",
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
		var upgrade = Flyway.configure().dataSource(dataSource).schemas(schema).load();
		assertThat(upgrade.migrate().migrationsExecuted).isOne();
		assertThat(jdbc.sql("SELECT balance_minor FROM foundation_upgrade.account_posted_balance")
				.query(Long.class).single()).isZero();
		assertThat(jdbc.sql("SELECT count(*) FROM foundation_upgrade.ledger_journal")
				.query(Long.class).single()).isZero();
		upgrade.validate();
		assertThat(upgrade.migrate().migrationsExecuted).isZero();
	}
}
