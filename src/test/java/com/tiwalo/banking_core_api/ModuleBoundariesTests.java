package com.tiwalo.banking_core_api;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModuleBoundariesTests {

	@Test
	void applicationModulesRespectTheirBoundaries() {
		ApplicationModules.of(BankingCoreApiApplication.class).verify();
	}
}
