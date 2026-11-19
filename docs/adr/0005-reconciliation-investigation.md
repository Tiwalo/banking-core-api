# ADR 0005 — Reconciliation and investigation access

## Capturing a report

A mapped operator with the `operations:investigate` client role can request a synchronous scan. The reconciliation application service takes a PostgreSQL REPEATABLE READ snapshot. One INSERT … SELECT consumes private accounting, transfer and audit read interfaces. Posting can continue while the report runs. Each POST creates a fresh run, and readers page through its saved findings.

The ledger check starts with every account, including unfunded accounts. It recomputes balances from posted entries, adding asset debits and liability credits and subtracting the opposite directions. PostgreSQL NUMERIC handles cumulative values beyond BIGINT. A missing projection is a discrepancy even when the computed balance is zero. The journal check compares debit and credit totals within each currency and verifies the two-leg shape, including empty journals.

The transfer check follows links in both directions and verifies the posting reference, initiating actor, timestamp, accounts, amounts and currency. A declined transfer is expected to have no journal. The audit check compares each journal with its posting audit and checks the actor links. A missing or orphaned audit produces a finding.

## Finding identity and audit

A finding reference is a namespaced SHA-256 digest of the check code, internal subject UUID and currency. It stays stable across runs for the same discrepancy. The API returns this reference, a code and, when relevant, the numeric evidence as decimal strings. Account IDs, recipient references, idempotency keys and payment payloads are excluded from saved findings and access records.

Any mapped investigator can read a known run. Each new report and each findings lookup commits an audit record before returning data. The audit records the internal operator, action, time, outcome and known run. A successful page lookup also records the requested limit and validated continuation reference. Missing runs and unknown cursors are recorded without persisting arbitrary caller input. The HTTP security chain rejects requests without the required authority before they reach the investigation service.

The runtime database login can execute the two operator routines. Each checks the supplied internal actor's operator mapping. Private discrepancy views, saved reports and the investigation log remain accessible through their owning interfaces. Schema owners manage these objects. Neither operator endpoint can change financial records.

## Operational limits

Each run scans the full simulated history and retains the captured discrepancies. Responses default to 20 findings and allow up to 100. Continuation seeks by finding reference in the stored report. Repeating a POST after response loss creates another audited report and has no financial effect. To investigate changes over time, compare stable references across runs.

V4 adds database objects independently of the posting path. During an application rollback, preserve the V4 report and audit tables and Flyway history. Corrective postings and reversals require a separately approved design.
