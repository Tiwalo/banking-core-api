package com.tiwalo.banking_core_api.ledger.internal;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("local-funding")
@ConditionalOnNotWebApplication
class LocalFundingCommand implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(LocalFundingCommand.class);
    private final SimulatedFunding funding;
    private final UUID reference;
    private final UUID clearing;
    private final UUID deposit;
    private final long amount;

    LocalFundingCommand(SimulatedFunding funding, @Value("${funding.reference}") UUID reference,
            @Value("${funding.clearing}") UUID clearing, @Value("${funding.deposit}") UUID deposit,
            @Value("${funding.amount-minor}") long amount) {
        this.funding = funding;
        this.reference = reference;
        this.clearing = clearing;
        this.deposit = deposit;
        this.amount = amount;
    }

    @Override
    public void run(String... args) {
        log.info("Funding journal {}", funding.post(reference, clearing, deposit, amount));
    }
}
