package com.tiwalo.banking_core_api.transfer.internal;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class InternalTransfers {
    private final JdbcClient database;
    private final long cap;

    InternalTransfers(JdbcClient database, @Value("${tiwalo.transfer.max-amount-minor}") long cap) {
        if (cap < 1) {
            throw new IllegalArgumentException("The configured transfer cap must be positive");
        }
        this.database = database;
        this.cap = cap;
    }

    record Receipt(UUID transferId, UUID sourceAccountId, String recipientReference, String currency,
                   String amountMinor, String status, Instant postedAt) {}
    record Outcome(String code, Receipt receipt) {}
    private record Decision(String code, UUID id) {}

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Outcome submit(UUID actor, TransferCommand command) {
        Decision result = database.sql("SELECT * FROM submit_internal_transfer(?, ?, ?, ?, 'PHP', ?, ?)")
                .param(actor).param(command.key()).param(command.source())
                .param(command.recipient().getBytes(StandardCharsets.UTF_8)).param(command.amount()).param(cap)
                .query((row, index) -> new Decision(row.getString("result"), row.getObject("result_id", UUID.class))).single();
        return new Outcome(result.code(), result.code().equals("POSTED") ? receipt(actor, result.id()).orElseThrow() : null);
    }

    @Transactional(readOnly = true)
    public Optional<Receipt> receipt(UUID actor, UUID transfer) {
        return database.sql("SELECT * FROM read_transfer_receipt(?, ?)").param(actor).param(transfer)
                .query((row, index) -> new Receipt(row.getObject("transfer_id", UUID.class),
                        row.getObject("source_id", UUID.class), row.getString("recipient_reference"),
                        row.getString("currency"), Long.toString(row.getLong("amount_minor")), row.getString("status"),
                        row.getObject("posted_at", OffsetDateTime.class).toInstant())).optional();
    }
}
