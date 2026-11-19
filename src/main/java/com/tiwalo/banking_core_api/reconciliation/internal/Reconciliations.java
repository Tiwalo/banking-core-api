package com.tiwalo.banking_core_api.reconciliation.internal;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
class Reconciliations {
    private final JdbcClient database;

    Reconciliations(JdbcClient database) {
        this.database = database;
    }

    record Run(UUID runId, Instant startedAt, long findingCount) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Finding(String reference, String code, String currency, String computedBalanceMinor,
                   String projectedBalanceMinor, String debitTotalMinor, String creditTotalMinor) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Page(UUID runId, List<Finding> findings, String nextCursor) {}
    record Lookup(String status, Page page) {}
    private record Row(String status, Finding finding) {}

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public Run capture(UUID actor) {
        return database.sql("SELECT * FROM run_reconciliation(?)").param(actor)
                .query((row, i) -> new Run(row.getObject("run_id", UUID.class),
                        row.getObject("started_at", OffsetDateTime.class).toInstant(), row.getLong("finding_count")))
                .single();
    }

    // Successful and unsuccessful lookups commit the attributable access record.
    @Transactional
    public Lookup findings(UUID actor, UUID run, String after, int limit) {
        var rows = database.sql("""
                SELECT p.page_status, (p.item).* FROM read_reconciliation_findings(:actor, :run, :after, :limit) p
                ORDER BY (p.item).reference COLLATE "C"
                """).param("actor", actor).param("run", run).param("after", after, java.sql.Types.VARCHAR).param("limit", limit)
                .query((row, i) -> {
                    String reference = row.getString("reference");
                    return new Row(row.getString("page_status"), reference == null ? null : new Finding(reference,
                            row.getString("code"), row.getString("currency"), row.getString("computed_minor"),
                            row.getString("projected_minor"), row.getString("debit_minor"), row.getString("credit_minor")));
                }).list();
        String status = rows.getFirst().status();
        if (!"OK".equals(status)) {
            return new Lookup(status, null);
        }
        List<Finding> findings = rows.stream().limit(limit).map(Row::finding).filter(java.util.Objects::nonNull).toList();
        String next = rows.size() > limit ? findings.getLast().reference() : null;
        return new Lookup(status, new Page(run, findings, next));
    }
}
