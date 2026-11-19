CREATE TABLE customer (
    customer_id uuid PRIMARY KEY DEFAULT gen_random_uuid()
);

CREATE TABLE security_actor (
    actor_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    issuer text NOT NULL CHECK (length(trim(issuer)) > 0),
    subject text NOT NULL CHECK (length(trim(subject)) > 0),
    actor_kind text NOT NULL CHECK (actor_kind IN ('CUSTOMER', 'OPERATOR')),
    customer_id uuid UNIQUE REFERENCES customer,
    UNIQUE (issuer, subject),
    CHECK ((actor_kind = 'CUSTOMER') = (customer_id IS NOT NULL))
);

CREATE TABLE account (
    account_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    classification text NOT NULL CHECK (classification IN ('DEPOSIT_LIABILITY', 'TEST_CLEARING_ASSET')),
    customer_id uuid REFERENCES customer,
    recipient_reference text UNIQUE,
    status text NOT NULL CHECK (status IN ('ACTIVE', 'FROZEN', 'CLOSED')),
    currency text NOT NULL CHECK (currency = 'PHP'),
    opening_balance_minor bigint NOT NULL DEFAULT 0 CHECK (opening_balance_minor = 0),
    page_cursor uuid NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    CHECK ((classification = 'DEPOSIT_LIABILITY') = (customer_id IS NOT NULL)),
    CHECK ((classification = 'DEPOSIT_LIABILITY') = (recipient_reference IS NOT NULL)),
    CHECK (recipient_reference IS NULL OR recipient_reference ~ '^rcp_[0-9a-f]{32}$')
);

CREATE INDEX account_customer_page ON account (customer_id, account_id);
