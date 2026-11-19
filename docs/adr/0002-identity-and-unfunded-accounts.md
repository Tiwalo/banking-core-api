# ADR 0002 — Customer identity and unfunded account reads

Accepted for the identity and accounts milestone.

## Decision

Identify an actor by the exact verified token issuer and subject. The database assigns that actor an internal ID and a customer or operator classification. Grant only the banking API client roles that apply to the stored classification. Account detail and pagination queries both include the internal customer ID. Recipient references are independently issued payment addresses. An account's random page cursor can resume a list only for its owner.

The published account shape requires an available balance before the ledger milestone. Every account starts unfunded. Flyway V1 enforces a zero opening balance in PostgreSQL, and both read endpoints serialize that value as the decimal string `"0"`.

## Consequences

An unmapped subject and a mapped operator receive a forbidden response on customer reads. A customer's lookup of another customer's account has the same response as an absent account. A change of issuer requires deliberate identity provisioning.

The ledger milestone must switch account reads to the authoritative posting projection before enabling the first funding operation. Preserve the immutable zero opening amount and introduce ledger storage in a new versioned migration.
