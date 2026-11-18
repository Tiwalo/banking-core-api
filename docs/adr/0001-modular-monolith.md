# ADR 0001 — Banking module boundaries

Accepted for the Tiwalo simulation.

## Context

An internal transfer must commit the transfer result, balanced journal, balance projections, posting audit, and idempotency outcome in one PostgreSQL transaction. The web and mobile clients need the same answer when either loses a response.

## Decision

Keep one Spring Boot application and one PostgreSQL database. Introduce each capability as a direct subpackage of `com.tiwalo.banking_core_api` when its first use case ships. The following ownership applies as capabilities are introduced.

- **Security** maps a verified issuer and subject to an internal actor. **Customer** owns customer identity. **Account** owns account ownership, classification, and lifecycle.
- **Ledger** is the sole posting interface and owns journals, entries, and derived balances. **Transfer** owns the command, actor-scoped idempotency outcome, receipt, and transaction boundary for an internal transfer.
- **Audit** records attributable actions and stable references. A successful posting and its audit commit together. **Reconciliation** compares committed entries with projections through read interfaces under a consistent snapshot.

Expose cross-module operations through the owning module's root package or a named interface. Keep persistence types and repositories internal. Declare allowed dependencies when each module is introduced. Add notification delivery only when a use case requires it.

## Consequences and verification

Coordinate financial writes synchronously. Both account rows must be locked in ascending immutable ID order before the posting decision. PostgreSQL and Flyway own persistence and schema evolution. A future required event must have its durable publication record in the posting transaction.

CI runs Spring Modulith verification on the application root. At the foundation milestone the root has no business modules. As modules arrive, the same test detects cycles, access to internals, and violations of declared dependencies. Financial milestones additionally need PostgreSQL tests that exercise locking, atomic rollback, and replay after a lost response.
