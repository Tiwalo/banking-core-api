-- Accounting modules publish these private read interfaces to reconciliation.
-- Sum in NUMERIC so a large turnover or a corrupt projection cannot wrap a BIGINT.
CREATE VIEW ledger_balance_discrepancy AS
WITH movements AS (
    SELECT e.account_id, e.currency,
           sum(CASE e.direction WHEN 'DEBIT' THEN e.amount_minor::numeric ELSE -e.amount_minor::numeric END) AS net_debit
    FROM ledger_entry e JOIN ledger_journal j USING (journal_id)
    GROUP BY e.account_id, e.currency
), balances AS (
    SELECT a.account_id, a.currency, b.balance_minor AS projected_minor,
           coalesce(CASE a.classification WHEN 'TEST_CLEARING_ASSET' THEN m.net_debit
                         WHEN 'DEPOSIT_LIABILITY' THEN -m.net_debit END, 0) AS computed_minor
    FROM account a LEFT JOIN ledger_balance b USING (account_id)
    LEFT JOIN movements m ON m.account_id = a.account_id AND m.currency = a.currency
)
SELECT CASE WHEN projected_minor IS NULL THEN 'MISSING_PROJECTION' ELSE 'BALANCE_MISMATCH' END AS code,
       account_id AS subject_id, currency, computed_minor, projected_minor
FROM balances WHERE projected_minor IS DISTINCT FROM computed_minor;

CREATE VIEW ledger_journal_discrepancy AS
SELECT j.journal_id AS subject_id, coalesce(e.currency, 'PHP') AS currency,
       coalesce(sum(e.amount_minor) FILTER (WHERE e.direction = 'DEBIT'), 0) AS debit_minor,
       coalesce(sum(e.amount_minor) FILTER (WHERE e.direction = 'CREDIT'), 0) AS credit_minor
FROM ledger_journal j LEFT JOIN ledger_entry e USING (journal_id)
GROUP BY j.journal_id, e.currency
HAVING count(e.journal_id) <> 2 OR sum(e.amount_minor) FILTER (WHERE e.direction = 'DEBIT')
               IS DISTINCT FROM sum(e.amount_minor) FILTER (WHERE e.direction = 'CREDIT');

CREATE VIEW transfer_journal_discrepancy AS
SELECT 'TRANSFER_JOURNAL_MISMATCH'::text AS code, t.transfer_id AS subject_id
FROM internal_transfer t
LEFT JOIN ledger_journal j ON j.journal_id = t.journal_id
LEFT JOIN ledger_entry d ON d.journal_id = j.journal_id AND d.direction = 'DEBIT'
LEFT JOIN ledger_entry c ON c.journal_id = j.journal_id AND c.direction = 'CREDIT'
WHERE (t.outcome = 'POSTED' AND
      (j.kind IS DISTINCT FROM 'INTERNAL_TRANSFER' OR j.reference IS DISTINCT FROM t.transfer_id
       OR j.customer_actor_id IS DISTINCT FROM t.actor_id OR j.posted_at IS DISTINCT FROM t.posted_at
       OR (d.account_id, d.currency, d.amount_minor) IS DISTINCT FROM (t.source_id, t.currency, t.amount_minor)
       OR (c.account_id, c.currency, c.amount_minor) IS DISTINCT FROM (t.destination_id, t.currency, t.amount_minor)))
   OR (t.outcome <> 'POSTED' AND t.journal_id IS NOT NULL)
UNION ALL
SELECT 'JOURNAL_WITHOUT_TRANSFER', j.journal_id FROM ledger_journal j WHERE j.kind = 'INTERNAL_TRANSFER'
AND NOT EXISTS (SELECT FROM internal_transfer t WHERE t.journal_id = j.journal_id AND t.outcome = 'POSTED');

CREATE VIEW audit_posting_discrepancy AS
SELECT CASE WHEN j.journal_id IS NULL THEN 'AUDIT_WITHOUT_JOURNAL'
            WHEN a.journal_id IS NULL THEN 'MISSING_POSTING_AUDIT'
            ELSE 'POSTING_AUDIT_MISMATCH' END AS code, coalesce(j.journal_id, a.journal_id) AS subject_id
FROM ledger_journal j FULL JOIN posting_audit a USING (journal_id)
WHERE j.journal_id IS NULL OR a.journal_id IS NULL OR
      (j.reference, j.actor_id, j.customer_actor_id) IS DISTINCT FROM (a.reference, a.actor_id, a.customer_actor_id)
   OR (a.actor_id IS NOT NULL AND NOT EXISTS (SELECT FROM funding_actor f WHERE f.actor_id = a.actor_id))
   OR (a.customer_actor_id IS NOT NULL AND NOT EXISTS (SELECT FROM security_actor s
            WHERE s.actor_id = a.customer_actor_id AND s.actor_kind = 'CUSTOMER'));

CREATE TABLE reconciliation_run (
    run_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    started_at timestamptz NOT NULL DEFAULT transaction_timestamp()
);
CREATE TABLE reconciliation_finding (
    run_id uuid NOT NULL REFERENCES reconciliation_run,
    reference text COLLATE "C" NOT NULL,
    code text NOT NULL,
    currency text,
    computed_minor numeric, projected_minor numeric, debit_minor numeric, credit_minor numeric,
    PRIMARY KEY (run_id, reference)
);
CREATE TABLE investigation_access (
    access_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    accessed_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    actor_id uuid NOT NULL REFERENCES security_actor,
    run_id uuid REFERENCES reconciliation_run,
    action text NOT NULL CHECK (action IN ('RUN', 'READ_FINDINGS')),
    outcome text NOT NULL CHECK (outcome IN ('OK', 'NOT_FOUND', 'INVALID_CURSOR')),
    after_reference text,
    page_limit integer CHECK (page_limit BETWEEN 1 AND 100),
    CHECK ((outcome = 'NOT_FOUND') = (run_id IS NULL)),
    FOREIGN KEY (run_id, after_reference) REFERENCES reconciliation_finding
);
CREATE TRIGGER immutable_report BEFORE UPDATE OR DELETE ON reconciliation_run
    FOR EACH ROW EXECUTE FUNCTION reject_posted_change();
CREATE TRIGGER immutable_finding BEFORE UPDATE OR DELETE ON reconciliation_finding
    FOR EACH ROW EXECUTE FUNCTION reject_posted_change();
CREATE TRIGGER immutable_investigation_access BEFORE UPDATE OR DELETE ON investigation_access
    FOR EACH ROW EXECUTE FUNCTION reject_posted_change();

CREATE FUNCTION require_investigator(p_actor uuid) RETURNS void LANGUAGE plpgsql
SET search_path = pg_catalog, "${flyway:defaultSchema}", pg_temp AS $$
BEGIN
    PERFORM actor_id FROM security_actor WHERE actor_id = p_actor AND actor_kind = 'OPERATOR' FOR SHARE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'operator permission required' USING ERRCODE = 'TW403';
    END IF;
END
$$;

CREATE FUNCTION run_reconciliation(p_actor uuid)
RETURNS TABLE (run_id uuid, started_at timestamptz, finding_count bigint)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, "${flyway:defaultSchema}", pg_temp AS $$
DECLARE
    report reconciliation_run;
BEGIN
    PERFORM require_investigator(p_actor);
    INSERT INTO reconciliation_run DEFAULT VALUES RETURNING * INTO report;
    -- One statement checks all financial relations at the same MVCC snapshot.
    -- The stored projection of each issue contains only allowlisted evidence.
    WITH issues(code, subject_id, currency, computed, projected, debit, credit) AS (
        SELECT code, subject_id, currency, computed_minor, projected_minor, NULL::numeric, NULL::numeric
            FROM ledger_balance_discrepancy
        UNION ALL SELECT 'JOURNAL_IMBALANCE', subject_id, currency, NULL, NULL, debit_minor, credit_minor
            FROM ledger_journal_discrepancy
        UNION ALL SELECT code, subject_id, NULL, NULL, NULL, NULL, NULL FROM transfer_journal_discrepancy
        UNION ALL SELECT code, subject_id, NULL, NULL, NULL, NULL, NULL FROM audit_posting_discrepancy
    )
    INSERT INTO reconciliation_finding (run_id, reference, code, currency, computed_minor, projected_minor, debit_minor, credit_minor)
        SELECT report.run_id, encode(sha256(convert_to('tiwalo:reconciliation:v1:' || code || ':' ||
                    subject_id::text || ':' || coalesce(currency, ''), 'UTF8')), 'hex'),
               code, currency, computed, projected, debit, credit FROM issues;
    GET DIAGNOSTICS finding_count = ROW_COUNT;
    INSERT INTO investigation_access (actor_id, run_id, action, outcome) VALUES (p_actor, report.run_id, 'RUN', 'OK');
    run_id := report.run_id;
    started_at := report.started_at;
    RETURN NEXT;
END
$$;

CREATE FUNCTION read_reconciliation_findings(p_actor uuid, p_run uuid, p_after text, p_limit integer)
RETURNS TABLE (page_status text, item reconciliation_finding)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, "${flyway:defaultSchema}", pg_temp AS $$
BEGIN
    PERFORM require_investigator(p_actor);
    IF p_limit IS NULL OR p_limit NOT BETWEEN 1 AND 100 THEN
        RAISE EXCEPTION 'invalid page size' USING ERRCODE = '22023';
    END IF;
    IF NOT EXISTS (SELECT FROM reconciliation_run WHERE run_id = p_run) THEN
        page_status := 'NOT_FOUND';
    ELSIF p_after IS NOT NULL AND NOT EXISTS (
        SELECT FROM reconciliation_finding WHERE run_id = p_run AND reference = p_after) THEN
        page_status := 'INVALID_CURSOR';
    ELSE
        page_status := 'OK';
    END IF;
    INSERT INTO investigation_access (actor_id, run_id, action, outcome, after_reference, page_limit)
        VALUES (p_actor, CASE WHEN page_status <> 'NOT_FOUND' THEN p_run END, 'READ_FINDINGS', page_status,
                CASE WHEN page_status = 'OK' THEN p_after END, p_limit);
    IF page_status = 'OK' THEN
        RETURN QUERY SELECT 'OK'::text, f FROM reconciliation_finding f
            WHERE f.run_id = p_run AND (p_after IS NULL OR f.reference > p_after)
            ORDER BY f.reference LIMIT p_limit + 1;
        IF FOUND THEN RETURN; END IF;
    END IF;
    RETURN NEXT; -- Empty page or a recorded unsuccessful lookup.
END
$$;

REVOKE ALL ON FUNCTION require_investigator(uuid), run_reconciliation(uuid),
    read_reconciliation_findings(uuid, uuid, text, integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION run_reconciliation(uuid), read_reconciliation_findings(uuid, uuid, text, integer)
    TO tiwalo_runtime;
