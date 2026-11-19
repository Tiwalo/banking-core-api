-- Serialize account creation with installing/backfilling its projection.
LOCK TABLE account IN SHARE ROW EXCLUSIVE MODE;

CREATE TABLE ledger_balance (
    account_id uuid PRIMARY KEY REFERENCES account,
    balance_minor bigint NOT NULL CHECK (balance_minor >= 0)
);

INSERT INTO ledger_balance SELECT account_id, opening_balance_minor FROM account;

CREATE FUNCTION initialize_ledger_balance() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, "${flyway:defaultSchema}", pg_temp AS $$
BEGIN
    INSERT INTO ledger_balance VALUES (NEW.account_id, 0);
    RETURN NEW;
END
$$;
REVOKE ALL ON FUNCTION initialize_ledger_balance() FROM PUBLIC;
CREATE TRIGGER account_opening_projection AFTER INSERT ON account
    FOR EACH ROW EXECUTE FUNCTION initialize_ledger_balance();

-- Published SQL read interface for the account module.
CREATE VIEW account_posted_balance AS SELECT account_id, balance_minor FROM ledger_balance;

-- A tooling identity is provisioned independently of customer/operator OIDC identities.
CREATE TABLE funding_actor (
    actor_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    login_name name UNIQUE NOT NULL
);

CREATE TABLE ledger_journal (
    journal_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    kind text NOT NULL CHECK (kind = 'SIMULATED_FUNDING'),
    actor_id uuid NOT NULL REFERENCES funding_actor,
    reference uuid NOT NULL,
    posted_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
    UNIQUE (actor_id, kind, reference)
);

CREATE TABLE ledger_entry (
    journal_id uuid NOT NULL REFERENCES ledger_journal,
    account_id uuid NOT NULL REFERENCES account,
    direction text NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
    currency text NOT NULL CHECK (currency = 'PHP'),
    amount_minor bigint NOT NULL CHECK (amount_minor > 0),
    PRIMARY KEY (journal_id, direction),
    UNIQUE (journal_id, account_id)
);
CREATE INDEX ledger_entry_account ON ledger_entry (account_id);

CREATE TABLE posting_audit (
    journal_id uuid PRIMARY KEY REFERENCES ledger_journal,
    actor_id uuid NOT NULL REFERENCES funding_actor,
    database_login name NOT NULL,
    reference uuid NOT NULL,
    recorded_at timestamptz NOT NULL DEFAULT transaction_timestamp()
);

CREATE FUNCTION reject_posted_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'posted history is immutable' USING ERRCODE = '23514';
END
$$;
CREATE TRIGGER immutable_journal BEFORE UPDATE OR DELETE ON ledger_journal
    FOR EACH ROW EXECUTE FUNCTION reject_posted_change();
CREATE TRIGGER immutable_entry BEFORE UPDATE OR DELETE ON ledger_entry
    FOR EACH ROW EXECUTE FUNCTION reject_posted_change();
CREATE TRIGGER immutable_posting_audit BEFORE UPDATE OR DELETE ON posting_audit
    FOR EACH ROW EXECUTE FUNCTION reject_posted_change();

CREATE TYPE ledger_leg AS (account_id uuid, direction text, currency text, amount_minor bigint);

-- The authenticated development login is the actor. Neither HTTP clients nor
-- operators can claim this identity. The two funding legs are the complete journal.
CREATE FUNCTION post_simulated_funding(p_reference uuid, p_legs ledger_leg[]) RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, "${flyway:defaultSchema}", pg_temp
SET lock_timeout = '5s' AS $$
DECLARE
    caller uuid;
    debit ledger_leg;
    credit ledger_leg;
    posted uuid;
    affected integer;
BEGIN
    IF p_reference IS NULL OR cardinality(p_legs) IS DISTINCT FROM 2 THEN
        RAISE EXCEPTION 'a funding reference and two legs are required' USING ERRCODE = '22023';
    END IF;
    SELECT * INTO debit FROM unnest(p_legs) WHERE direction = 'DEBIT';
    SELECT * INTO credit FROM unnest(p_legs) WHERE direction = 'CREDIT';
    IF debit.account_id IS NULL OR credit.account_id IS NULL OR debit.account_id = credit.account_id
       OR debit.currency IS DISTINCT FROM 'PHP' OR credit.currency IS DISTINCT FROM 'PHP'
       OR debit.amount_minor IS NULL OR debit.amount_minor <= 0
       OR credit.amount_minor IS DISTINCT FROM debit.amount_minor THEN
        RAISE EXCEPTION 'invalid or unbalanced funding legs' USING ERRCODE = '22023';
    END IF;

    SELECT actor_id INTO caller FROM funding_actor WHERE login_name = session_user FOR SHARE;
    IF caller IS NULL THEN
        RAISE EXCEPTION 'unregistered funding identity' USING ERRCODE = '42501';
    END IF;
    INSERT INTO ledger_journal (actor_id, kind, reference)
        VALUES (caller, 'SIMULATED_FUNDING', p_reference)
        ON CONFLICT (actor_id, kind, reference) DO NOTHING RETURNING journal_id INTO posted;
    IF posted IS NULL THEN
        -- At READ COMMITTED this statement sees the winning transaction. Compare
        -- the typed, normalized request to its immutable legs, independent of order.
        SELECT j.journal_id INTO posted FROM ledger_journal j
        JOIN ledger_entry d ON d.journal_id = j.journal_id AND d.direction = 'DEBIT'
        JOIN ledger_entry c ON c.journal_id = j.journal_id AND c.direction = 'CREDIT'
        WHERE j.actor_id = caller AND j.kind = 'SIMULATED_FUNDING' AND j.reference = p_reference
          AND d.account_id = debit.account_id AND c.account_id = credit.account_id
          AND d.amount_minor = debit.amount_minor AND c.amount_minor = credit.amount_minor;
        IF posted IS NULL THEN
            RAISE EXCEPTION 'funding reference reused with a different request' USING ERRCODE = '22023';
        END IF;
        RETURN posted;
    END IF;

    PERFORM account_id FROM account WHERE account_id IN (debit.account_id, credit.account_id)
        ORDER BY account_id FOR UPDATE;
    GET DIAGNOSTICS affected = ROW_COUNT;
    IF affected <> 2 OR NOT EXISTS (SELECT FROM account WHERE account_id = debit.account_id AND status = 'ACTIVE'
                   AND classification = 'TEST_CLEARING_ASSET' AND currency = debit.currency)
       OR NOT EXISTS (SELECT FROM account WHERE account_id = credit.account_id AND status = 'ACTIVE'
                      AND classification = 'DEPOSIT_LIABILITY' AND currency = credit.currency) THEN
        RAISE EXCEPTION 'funding requires active PHP clearing and deposit accounts' USING ERRCODE = '22023';
    END IF;

    INSERT INTO ledger_entry (journal_id, account_id, direction, currency, amount_minor)
        SELECT posted, leg.account_id, leg.direction, leg.currency, leg.amount_minor FROM unnest(p_legs) AS leg;
    -- An asset debit and a liability credit both increase the normal balance.
    -- Native bigint addition raises 22003 on overflow and aborts the whole post.
    UPDATE ledger_balance SET balance_minor = balance_minor + debit.amount_minor
        WHERE account_id IN (debit.account_id, credit.account_id);
    GET DIAGNOSTICS affected = ROW_COUNT;
    IF affected <> 2 THEN
        RAISE EXCEPTION 'missing account projection' USING ERRCODE = '23514';
    END IF;
    INSERT INTO posting_audit (journal_id, actor_id, database_login, reference)
        VALUES (posted, caller, session_user, p_reference);
    RETURN posted;
END
$$;

REVOKE ALL ON FUNCTION post_simulated_funding(uuid, ledger_leg[]) FROM PUBLIC;
GRANT USAGE ON SCHEMA "${flyway:defaultSchema}" TO tiwalo_runtime, tiwalo_dev_funder;
GRANT SELECT ON security_actor, account, account_posted_balance TO tiwalo_runtime;
GRANT EXECUTE ON FUNCTION post_simulated_funding(uuid, ledger_leg[]) TO tiwalo_dev_funder;
