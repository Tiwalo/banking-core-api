-- Apply explicitly to a local database after Flyway has migrated it.
\set ON_ERROR_STOP on
BEGIN;
INSERT INTO customer (customer_id) VALUES ('094084de-db04-4039-b35d-b22ca2171282')
ON CONFLICT (customer_id) DO NOTHING;

INSERT INTO security_actor (issuer, subject, actor_kind, customer_id) VALUES
    (:'issuer', 'e30f7899-f8e4-4471-af1d-b46ec3ccbe92', 'CUSTOMER', '094084de-db04-4039-b35d-b22ca2171282'),
    (:'issuer', '3dd479c3-5a21-4c57-971d-559e531aa39c', 'OPERATOR', NULL)
ON CONFLICT (issuer, subject) DO NOTHING;

INSERT INTO account (account_id, classification, customer_id, recipient_reference, status, currency)
VALUES ('9e4777f9-8de5-462c-8366-9d8aa9766096', 'DEPOSIT_LIABILITY', '094084de-db04-4039-b35d-b22ca2171282',
        'rcp_' || replace(gen_random_uuid()::text, '-', ''), 'ACTIVE', 'PHP')
ON CONFLICT (account_id) DO NOTHING;
COMMIT;
