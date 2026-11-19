package com.tiwalo.banking_core_api.ledger.internal;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
class SimulatedFunding {

    private final JdbcClient database;

    SimulatedFunding(JdbcClient database) {
        this.database = database;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public UUID post(UUID reference, UUID clearing, UUID deposit, long amountMinor) {
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("Funding requires a positive amount in centavos");
        }
        return database.sql("""
                SELECT post_simulated_funding(:reference, ARRAY[
                    ROW(:clearing, 'DEBIT', 'PHP', :amount)::ledger_leg,
                    ROW(:deposit, 'CREDIT', 'PHP', :amount)::ledger_leg
                ])
                """)
                .param("reference", reference).param("clearing", clearing)
                .param("deposit", deposit).param("amount", amountMinor)
                .query(UUID.class).single();
    }
}
