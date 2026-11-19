package com.tiwalo.banking_core_api.account.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.tiwalo.banking_core_api.customer.CustomerId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
class OwnedAccounts {

    private static final String OWNED_DEPOSITS = """
            SELECT account_id, recipient_reference, status, currency, balance_minor, page_cursor
            FROM account JOIN account_posted_balance USING (account_id)
            WHERE customer_id = :owner AND classification = 'DEPOSIT_LIABILITY'
            """;
    private final JdbcClient database;

    OwnedAccounts(JdbcClient database) {
        this.database = database;
    }

    Optional<AccountView> find(CustomerId owner, UUID id) {
        return database.sql(OWNED_DEPOSITS + " AND account_id = :id")
                .param("owner", owner.value()).param("id", id)
                .query((row, index) -> account(row)).optional();
    }

    AccountPage list(CustomerId owner, int limit, String cursor) {
        if (limit < 1 || limit > 100) {
            throw new InvalidAccountQuery();
        }
        String sql = OWNED_DEPOSITS;
        UUID after = null;
        if (cursor != null) {
            after = database.sql("""
                    SELECT account_id FROM account
                    WHERE page_cursor = :cursor AND customer_id = :owner AND classification = 'DEPOSIT_LIABILITY'
                    """)
                    .param("cursor", InvalidAccountQuery.parseUuid(cursor)).param("owner", owner.value())
                    .query(UUID.class).optional().orElseThrow(InvalidAccountQuery::new);
            sql += " AND account_id > :after";
        }
        var query = database.sql(sql + " ORDER BY account_id LIMIT :limit")
                .param("owner", owner.value()).param("limit", limit + 1);
        if (after != null) {
            query.param("after", after);
        }
        List<PageRow> rows = query.query((row, index) -> new PageRow(account(row),
                row.getObject("page_cursor", UUID.class))).list();
        boolean hasMore = rows.size() > limit;
        return new AccountPage(rows.stream().limit(limit).map(PageRow::account).toList(),
                hasMore ? rows.get(limit - 1).cursor().toString() : null);
    }

    private static AccountView account(ResultSet row) throws SQLException {
        return new AccountView(row.getObject("account_id", UUID.class), row.getString("recipient_reference"),
                row.getString("status"), row.getString("currency"), Long.toString(row.getLong("balance_minor")));
    }

    private record PageRow(AccountView account, UUID cursor) {}
}
