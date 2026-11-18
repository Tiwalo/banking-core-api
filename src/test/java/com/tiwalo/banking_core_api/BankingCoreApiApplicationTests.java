package com.tiwalo.banking_core_api;

import java.sql.SQLException;
import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class BankingCoreApiApplicationTests {

	@Test
	void applicationConnectsToPostgreSql(@Autowired DataSource dataSource) throws SQLException {
		try (var connection = dataSource.getConnection()) {
			var database = connection.getMetaData();
			assertThat(database.getDatabaseProductName()).isEqualTo("PostgreSQL");
			assertThat(database.getDatabaseMajorVersion()).isEqualTo(17);
		}
	}
}
