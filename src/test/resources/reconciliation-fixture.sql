-- This suite owns a disposable PostgreSQL container.
TRUNCATE customer, security_actor, ledger_journal, funding_actor, reconciliation_run CASCADE;
INSERT INTO customer (customer_id) VALUES ('cf75589d-d934-440b-961f-9cbad62c2883');
INSERT INTO security_actor (issuer, subject, actor_kind, customer_id) VALUES
    ('https://id.example.invalid/realms/tiwalo', 'sender', 'CUSTOMER', 'cf75589d-d934-440b-961f-9cbad62c2883'),
    ('https://id.example.invalid/realms/tiwalo', 'investigator', 'OPERATOR', NULL),
    ('https://id.example.invalid/realms/tiwalo', 'reviewer', 'OPERATOR', NULL);
INSERT INTO funding_actor (login_name) VALUES (session_user);
INSERT INTO account (account_id, classification, customer_id, recipient_reference, status, currency) VALUES
    ('4a30ad95-f7d0-4272-9cc0-5cf79bda9ec5', 'TEST_CLEARING_ASSET', NULL, NULL, 'ACTIVE', 'PHP'),
    ('ce31d1d4-fef0-48ff-96f0-ea2740da3aaa', 'DEPOSIT_LIABILITY', 'cf75589d-d934-440b-961f-9cbad62c2883',
     'rcp_00000000000000000000000000000001', 'ACTIVE', 'PHP'),
    ('1fa4a253-b218-47ca-8f94-3df97b11a34a', 'DEPOSIT_LIABILITY', 'cf75589d-d934-440b-961f-9cbad62c2883',
     'rcp_00000000000000000000000000000002', 'ACTIVE', 'PHP');
SELECT post_simulated_funding(gen_random_uuid(), ARRAY[
    ROW('4a30ad95-f7d0-4272-9cc0-5cf79bda9ec5','DEBIT','PHP',110)::ledger_leg,
    ROW('ce31d1d4-fef0-48ff-96f0-ea2740da3aaa','CREDIT','PHP',110)::ledger_leg]);
SELECT * FROM submit_internal_transfer((SELECT actor_id FROM security_actor WHERE subject = 'sender'),
    'synthetic-payment-marker','ce31d1d4-fef0-48ff-96f0-ea2740da3aaa',
    convert_to('rcp_00000000000000000000000000000002','UTF8'), 'PHP',30,1000);
