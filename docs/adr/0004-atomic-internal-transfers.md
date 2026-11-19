# ADR 0004 — Atomic internal transfers

## Posting and replay

The transfer application service owns one READ COMMITTED transaction. It obtains the internal actor from the verified access token and the positive per-transfer cap from server configuration. The runtime login can execute the transfer command and receipt query. Only the command can invoke the private ledger posting routine. The operator role grants investigation authority only.

The command first verifies customer identity and source ownership. It reserves a unique key for that actor and operation, along with a canonical fingerprint of the source UUID, exact recipient bytes, currency and integer amount. The key's deferred foreign key requires a durable outcome at commit. A competing claim waits for its predecessor. Replays verify current source ownership before returning the stored outcome. A mismatched fingerprint yields a conflict. An authorization failure or a technical rollback releases any new claim.

A new command resolves the recipient and locks the account rows in ascending UUID order, the same order as funding. It verifies ownership, the recipient reference, deposit classifications, account status, the configured cap, available funds and destination headroom under those locks. A destination that cannot accept the amount returns the existing opaque ACCOUNT_UNAVAILABLE decline. Each business decline commits a transfer outcome and key. Only a successful transfer writes a journal, two entries, balance changes and a posting audit. The sender liability is debited and the recipient liability credited by the same positive number of centavos. The audit records the initiating customer actor, API database login and transfer ID.

The initiating actor can retrieve the posted receipt while they own the source account. The immutable receipt preserves the original reference, amount and posting timestamp. Customers can pay another customer's active deposit account through its opaque reference. That payment grants neither customer access to the other's account or receipts.

## Operations and verification

Lock waits are bounded at five seconds. Clients retain and reuse the original key and logical request after contention or an uncertain response. Financial history retains successes and definite declines indefinitely. A changed cap or later funding applies to new intents. Configure the same positive cap across all API instances.

V3 adds transfer history and idempotency records and extends journals and audits with customer attribution. Existing funding actors, journal references and replay semantics are preserved. On rollback, disable new submissions if necessary and keep V3 history and receipt/replay access available. Restore the compatible service before clients retry outstanding intents.

HTTP tests run as the restricted API login against PostgreSQL with signed customer tokens. They hold independent transactions at observed locks, fail a deferred trigger at commit, and drop an HTTP response after successful posting. Assertions cover balances, transfer records, idempotency keys, ledger entries, journal references and audit attribution.
