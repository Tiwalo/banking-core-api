# ADR 0003 — Atomic simulated funding

Accepted for the simulated ledger milestone.

## Posting and account reads

The first posting operation funds an active PHP deposit from an active test clearing asset. A named Spring application service starts a PostgreSQL transaction at READ COMMITTED. The database accepts the complete pair of typed legs. It checks currency, direction, classification, status, distinct accounts and equal positive amounts. The clearing asset debit and deposit liability credit increase both accounts' normal balances. PostgreSQL checks each BIGINT addition for overflow.

The journal reserves a unique reference per internal funding actor and operation. A new posting locks both account rows in ascending UUID order before checking account state. Every future posting or account lifecycle writer must take the same account locks before changing financial state. Lock waits have a five-second limit. A rolled-back attempt releases its reference. Concurrent identical requests converge on one committed journal. Replay compares the immutable debit and credit, independent of input order. Keep journals and references for the lifetime of the simulated history.

One transaction writes the journal, two entries, two projections and attributable posting audit. Each journal can contain exactly one entry per direction. The only granted posting function inserts the complete pair. History tables reject updates and deletes. A future correction requires a new linked journal. The account module joins the ledger's read-only `account_posted_balance` view with its own owner-scoped query. With no holds, the deposit's posted balance is available to spend. Both migrated and newly opened accounts start with a zero projection.

## Authority

Flyway runs as the schema owner in a separate migration process. Bootstrap provisions the PostgreSQL privilege groups `tiwalo_runtime` and `tiwalo_dev_funder` before V2 runs. Use a schema whose CREATE privilege is restricted to its trusted owner. The HTTP login receives read access to the identity, account and balance interfaces. A dedicated local seed login can execute the ledger routine. Its SECURITY DEFINER search path starts with pg_catalog, then the trusted migration schema, and ends with pg_temp.

The function resolves the authenticated database login to a provisioned, stable funding actor UUID. It records both identities and the funding reference in the posting audit. Funding is a development tooling operation. Customer and operator tokens grant no funding authority. Restrict schema-owner credentials and the ability to grant roles to database administration.

## Verification and recovery

PostgreSQL tests exercise clean installation and V1 backfill, duplicate references on independent connections, input checks, overflow, role grants and rollback on audit failure. HTTP tests check that owners see posted centavos above the JavaScript safe-integer limit. Retry an uncertain funding outcome with the original request and reference. Once funding exists, a rollback must retain ledger-backed account reads and all financial history.
