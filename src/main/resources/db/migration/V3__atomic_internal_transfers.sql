-- Keep the V2 funding identities and foreign keys. A transfer instead names
-- the internal customer actor resolved from the verified access token.
ALTER TABLE ledger_journal
    ALTER COLUMN actor_id DROP NOT NULL,
    ADD COLUMN customer_actor_id uuid REFERENCES security_actor,
    DROP CONSTRAINT ledger_journal_kind_check,
    ADD CHECK ((kind = 'SIMULATED_FUNDING' AND actor_id IS NOT NULL AND customer_actor_id IS NULL)
            OR (kind = 'INTERNAL_TRANSFER' AND actor_id IS NULL AND customer_actor_id IS NOT NULL));
CREATE UNIQUE INDEX ledger_transfer_reference ON ledger_journal (reference) WHERE kind = 'INTERNAL_TRANSFER';
ALTER TABLE posting_audit
    ALTER COLUMN actor_id DROP NOT NULL,
    ADD COLUMN customer_actor_id uuid REFERENCES security_actor,
    ADD CHECK (num_nonnulls(actor_id, customer_actor_id) = 1);

CREATE TABLE internal_transfer (
    transfer_id uuid PRIMARY KEY,
    actor_id uuid NOT NULL REFERENCES security_actor,
    source_id uuid NOT NULL REFERENCES account,
    destination_id uuid REFERENCES account,
    recipient_reference text,
    amount_minor bigint NOT NULL CHECK (amount_minor > 0),
    currency text NOT NULL CHECK (currency = 'PHP'),
    outcome text NOT NULL CHECK (outcome IN ('POSTED','INSUFFICIENT_FUNDS','TRANSFER_LIMIT_EXCEEDED',
                                              'SAME_ACCOUNT','ACCOUNT_UNAVAILABLE')),
    journal_id uuid UNIQUE REFERENCES ledger_journal,
    posted_at timestamptz,
    UNIQUE (actor_id, transfer_id),
    CHECK ((outcome = 'POSTED' AND journal_id IS NOT NULL AND posted_at IS NOT NULL
            AND recipient_reference IS NOT NULL AND destination_id IS NOT NULL AND source_id <> destination_id)
        OR (outcome <> 'POSTED' AND journal_id IS NULL AND posted_at IS NULL
            AND recipient_reference IS NULL AND destination_id IS NULL))
);

CREATE TABLE transfer_idempotency (
    actor_id uuid NOT NULL REFERENCES security_actor,
    operation text NOT NULL CHECK (operation = 'CREATE_INTERNAL_TRANSFER'),
    request_key text COLLATE "C" NOT NULL CHECK (request_key ~ '^[A-Za-z0-9._~-]{1,128}$'),
    fingerprint jsonb NOT NULL,
    transfer_id uuid NOT NULL UNIQUE,
    PRIMARY KEY (actor_id, operation, request_key),
    FOREIGN KEY (actor_id, transfer_id) REFERENCES internal_transfer (actor_id, transfer_id)
        DEFERRABLE INITIALLY DEFERRED
);
CREATE TRIGGER immutable_transfer BEFORE UPDATE OR DELETE ON internal_transfer
    FOR EACH ROW EXECUTE FUNCTION reject_posted_change();
CREATE TRIGGER immutable_transfer_key BEFORE UPDATE OR DELETE ON transfer_idempotency
    FOR EACH ROW EXECUTE FUNCTION reject_posted_change();

-- Private ledger interface. The caller already holds both account row locks.
CREATE FUNCTION ledger_post_transfer(p_actor uuid, p_transfer uuid, p_source uuid, p_destination uuid,
                                     p_amount bigint) RETURNS ledger_journal
LANGUAGE plpgsql SET search_path = pg_catalog, "${flyway:defaultSchema}", pg_temp AS $$
DECLARE
    journal ledger_journal;
    affected integer;
BEGIN
    INSERT INTO ledger_journal (kind, customer_actor_id, reference)
        VALUES ('INTERNAL_TRANSFER', p_actor, p_transfer) RETURNING * INTO journal;
    INSERT INTO ledger_entry (journal_id, account_id, direction, currency, amount_minor) VALUES
        (journal.journal_id, p_source, 'DEBIT', 'PHP', p_amount),
        (journal.journal_id, p_destination, 'CREDIT', 'PHP', p_amount);
    UPDATE ledger_balance SET balance_minor = CASE account_id
        WHEN p_source THEN balance_minor - p_amount ELSE balance_minor + p_amount END
        WHERE account_id IN (p_source, p_destination);
    GET DIAGNOSTICS affected = ROW_COUNT;
    IF affected <> 2 THEN
        RAISE EXCEPTION 'missing posting projection' USING ERRCODE = '23514';
    END IF;
    INSERT INTO posting_audit (journal_id, customer_actor_id, database_login, reference)
        VALUES (journal.journal_id, p_actor, session_user, p_transfer);
    RETURN journal;
END
$$;
REVOKE ALL ON FUNCTION ledger_post_transfer(uuid, uuid, uuid, uuid, bigint) FROM PUBLIC;

CREATE FUNCTION submit_internal_transfer(p_actor uuid, p_key text, p_source uuid, p_recipient bytea,
                                         p_currency text, p_amount bigint, p_cap bigint)
RETURNS TABLE (result text, result_id uuid)
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, "${flyway:defaultSchema}", pg_temp
SET lock_timeout = '5s' AS $$
DECLARE
    owner_id uuid;
    recipient_id uuid;
    locked account;
    destination account;
    source_account account;
    address text;
    logical_request jsonb;
    claim transfer_idempotency;
    journal ledger_journal;
    source_balance bigint;
    destination_balance bigint;
BEGIN
    IF p_key IS NULL OR p_key COLLATE "C" !~ '^[A-Za-z0-9._~-]{1,128}$' OR p_source IS NULL
       OR p_currency IS DISTINCT FROM 'PHP' OR p_amount IS NULL OR p_amount < 1
       OR p_cap IS NULL OR p_cap < 1 OR p_recipient IS NULL OR octet_length(p_recipient) NOT BETWEEN 1 AND 512 THEN
        RAISE EXCEPTION 'invalid transfer command' USING ERRCODE = '22023';
    END IF;
    SELECT customer_id INTO owner_id FROM security_actor
        WHERE actor_id = p_actor AND actor_kind = 'CUSTOMER' FOR SHARE;
    IF owner_id IS NULL OR NOT EXISTS (SELECT FROM account WHERE account_id = p_source
            AND customer_id = owner_id AND classification = 'DEPOSIT_LIABILITY') THEN
        RAISE EXCEPTION 'resource not found' USING ERRCODE = 'TW404';
    END IF;

    -- Canonical typed tuple. Encoding the opaque UTF-8 address also preserves
    -- unresolvable references containing a JSON NUL, which PostgreSQL text cannot store.
    logical_request := jsonb_build_array(p_source, encode(p_recipient, 'hex'), p_currency, p_amount);
    INSERT INTO transfer_idempotency (actor_id, operation, request_key, fingerprint, transfer_id)
        VALUES (p_actor, 'CREATE_INTERNAL_TRANSFER', p_key, logical_request, gen_random_uuid())
        ON CONFLICT (actor_id, operation, request_key) DO NOTHING RETURNING * INTO claim;
    IF NOT FOUND THEN
        PERFORM account_id FROM account WHERE account_id = p_source AND customer_id = owner_id
            AND classification = 'DEPOSIT_LIABILITY' FOR SHARE;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'resource not found' USING ERRCODE = 'TW404';
        END IF;
        SELECT * INTO STRICT claim FROM transfer_idempotency WHERE actor_id = p_actor
            AND operation = 'CREATE_INTERNAL_TRANSFER' AND request_key = p_key;
        IF claim.fingerprint <> logical_request THEN
            RETURN QUERY SELECT 'IDEMPOTENCY_CONFLICT'::text, NULL::uuid;
        ELSE
            RETURN QUERY SELECT t.outcome, t.transfer_id FROM internal_transfer t WHERE t.transfer_id = claim.transfer_id;
        END IF;
        RETURN;
    END IF;

    IF position(decode('00','hex') IN p_recipient) = 0 THEN
        address := convert_from(p_recipient, 'UTF8');
        SELECT account_id INTO recipient_id FROM account WHERE recipient_reference = address;
    END IF;
    -- Never acquire a source-only row lock or account FK before the ordered pair.
    FOR locked IN SELECT * FROM account WHERE account_id IN (p_source, recipient_id)
            ORDER BY account_id FOR UPDATE LOOP
        IF locked.account_id = p_source THEN
            source_account := locked;
        END IF;
        IF locked.account_id = recipient_id AND locked.recipient_reference = address THEN
            destination := locked;
        END IF;
    END LOOP;
    IF source_account.customer_id IS DISTINCT FROM owner_id OR source_account.classification <> 'DEPOSIT_LIABILITY' THEN
        RAISE EXCEPTION 'resource not found' USING ERRCODE = 'TW404';
    END IF;
    IF destination.account_id = p_source THEN
        result := 'SAME_ACCOUNT';
    ELSIF source_account.status <> 'ACTIVE' OR destination.account_id IS NULL OR destination.status <> 'ACTIVE'
          OR destination.classification <> 'DEPOSIT_LIABILITY' THEN
        result := 'ACCOUNT_UNAVAILABLE';
    ELSIF p_amount > p_cap THEN
        result := 'TRANSFER_LIMIT_EXCEEDED';
    ELSE
        SELECT balance_minor INTO STRICT source_balance FROM ledger_balance WHERE account_id = p_source;
        SELECT balance_minor INTO STRICT destination_balance FROM ledger_balance WHERE account_id = destination.account_id;
        IF source_balance < p_amount THEN
            result := 'INSUFFICIENT_FUNDS';
        ELSIF destination_balance > 9223372036854775807::bigint - p_amount THEN
            result := 'ACCOUNT_UNAVAILABLE';
        ELSE
            journal := ledger_post_transfer(p_actor, claim.transfer_id, p_source, destination.account_id, p_amount);
            result := 'POSTED';
        END IF;
    END IF;

    result_id := claim.transfer_id;
    INSERT INTO internal_transfer (transfer_id, actor_id, source_id, destination_id, recipient_reference,
                                  amount_minor, currency, outcome, journal_id, posted_at)
        VALUES (result_id, p_actor, p_source, CASE WHEN result = 'POSTED' THEN destination.account_id END,
                CASE WHEN result = 'POSTED' THEN address END, p_amount, p_currency, result, journal.journal_id, journal.posted_at);
    RETURN NEXT;
END
$$;

CREATE FUNCTION read_transfer_receipt(p_actor uuid, p_transfer uuid)
RETURNS TABLE (transfer_id uuid, source_id uuid, recipient_reference text, currency text, amount_minor bigint,
               status text, posted_at timestamptz)
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, "${flyway:defaultSchema}", pg_temp AS $$
    SELECT t.transfer_id, t.source_id, t.recipient_reference, t.currency, t.amount_minor, t.outcome, t.posted_at
    FROM internal_transfer t JOIN security_actor s ON s.actor_id = t.actor_id
    JOIN account a ON a.account_id = t.source_id AND a.customer_id = s.customer_id
    WHERE t.transfer_id = p_transfer AND t.actor_id = p_actor AND s.actor_kind = 'CUSTOMER' AND t.outcome = 'POSTED'
$$;

REVOKE ALL ON FUNCTION submit_internal_transfer(uuid, text, uuid, bytea, text, bigint, bigint),
    read_transfer_receipt(uuid, uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION submit_internal_transfer(uuid, text, uuid, bytea, text, bigint, bigint),
    read_transfer_receipt(uuid, uuid) TO tiwalo_runtime;
