INSERT INTO customer (customer_id) VALUES
    ('10000000-0000-0000-0000-000000000001'),
    ('10000000-0000-0000-0000-000000000002'),
    ('10000000-0000-0000-0000-000000000003');

INSERT INTO security_actor (issuer, subject, actor_kind, customer_id) VALUES
    ('https://id.example.invalid/realms/tiwalo', 'first', 'CUSTOMER', '10000000-0000-0000-0000-000000000001'),
    ('https://id.example.invalid/realms/tiwalo', 'second', 'CUSTOMER', '10000000-0000-0000-0000-000000000002'),
    ('https://other.example.invalid', 'other-issuer-only', 'CUSTOMER', '10000000-0000-0000-0000-000000000003'),
    ('https://id.example.invalid/realms/tiwalo', 'operator', 'OPERATOR', NULL);

INSERT INTO account (account_id, customer_id, classification, status, currency, recipient_reference)
SELECT ('00000000-0000-4000-8000-' || lpad(i::text, 12, '0'))::uuid,
       '10000000-0000-0000-0000-000000000001', 'DEPOSIT_LIABILITY', 'ACTIVE', 'PHP',
       'rcp_' || replace(gen_random_uuid()::text, '-', '')
FROM generate_series(1, 21) AS ids(i);

INSERT INTO account (account_id, customer_id, classification, status, currency, recipient_reference) VALUES
    ('00000000-0000-4000-8000-000000000022', '10000000-0000-0000-0000-000000000002',
     'DEPOSIT_LIABILITY', 'FROZEN', 'PHP', 'rcp_0123456789abcdef0123456789abcdef'),
    ('00000000-0000-4000-8000-000000000023', NULL, 'TEST_CLEARING_ASSET', 'ACTIVE', 'PHP', NULL);
