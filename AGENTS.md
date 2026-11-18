# AGENTS.md — Tiwalo Banking Core API

## 1. Purpose and scope

This repository, `banking-core-api`, contains the **authoritative backend** for **Tiwalo**, a portfolio-grade, simulated Philippine digital banking and payments platform. It serves two separate repositories:

- `banking-web` — Next.js customer banking and bank-operations interfaces.
- `banking-mobile` — React Native / Expo Android and iOS customer application.

**Engineering objective:** demonstrate senior-level Java/Spring Boot design, financial correctness, transaction isolation, security, API design, auditability, resilience, and testable architectural decisions.

This is a **simulation**, not a licensed bank or payment service. Use only synthetic customer identities and fictional money. Never introduce real deposit collection, real payment rails, production banking credentials, or actual customer financial data.

## 2. Agent operating procedure

Before changing anything:

1. Inspect `pom.xml`, the current source tree, existing tests, migrations, API contracts, and relevant ADRs. **The repository is the source of truth for what is actually implemented.**
2. Identify the current Git branch and requested milestone. Do not assume an empty scaffold or overwrite generator-created files unnecessarily.
3. Read relevant installed Spring Boot skills (see §11), if available. Use their guidance only when compatible with this file and the approved design.
4. Present a concise plan for changes that affect architecture, financial postings, authorization, schema, API compatibility, or multiple modules.
5. Implement only the requested milestone. Do not silently add future features, new infrastructure, or unrelated refactors.
6. Execute relevant tests and report what ran, what failed, and what could not be verified. Never claim a test passed if it was not executed.

When asked to **plan**, inspect and propose; **do not edit files or execute destructive operations**. When asked to implement, make focused changes and include tests and documentation.

If requirements conflict or a banking invariant is unclear, identify the conflict and request a decision before implementing the unsafe or ambiguous part. Prefer a narrowly scoped, safe partial implementation over inventing financial semantics.

## 3. Approved technical direction

- **Language:** Java 25 LTS, unless the checked-in project explicitly targets another supported LTS.
- **Framework:** Spring Boot 4.x, Maven; choose a compatible Spring Modulith 2.x release.
- **Architecture:** one deployable **modular monolith**, not a collection of microservices.
- **Database:** PostgreSQL; Flyway is the sole source of schema migrations.
- **Persistence:** Spring Data JPA for conventional entities; prefer explicit Spring JDBC/SQL where ledger locking and posting behavior require precision.
- **Security:** Spring Security OAuth2 Resource Server, with Keycloak as the local OIDC issuer.
- **API:** versioned JSON REST under `/api/v1`; OpenAPI 3.1 contract owned by this repository.
- **Tests:** Spring Boot-managed JUnit Jupiter, AssertJ, Spring Boot integration tests, Testcontainers with **real PostgreSQL**, and API/contract tests.
- **Build/ops:** Maven Wrapper when available, Docker Compose for local dependencies, GitHub Actions for CI; Actuator/Micrometer/OpenTelemetry as observability needs arise.

**Do not introduce** Kafka, Redis, Kubernetes, an external workflow engine, a separate AI service, or extra microservices in the foundation/MVP without an approved ADR and concrete requirement. Avoid speculative dependencies. Pin and verify compatible versions; never assume an artifact/API exists without checking.

## 4. Module boundaries and Java conventions

Prefer packages organized by business capability under the existing application root package:

```text
customer/
account/
ledger/
transfer/
reconciliation/
audit/
notification/
security/
shared/             # narrowly scoped technical primitives only
```

Within a module, separate API, application/use-case, domain, and infrastructure responsibilities **only where useful**. Avoid a global `controllers/`, `services/`, or `repositories/` directory that mixes domains.

- Use Spring Modulith verification tests to protect application-module boundaries; expose only explicit cross-module interfaces/events.
- Keep controllers thin. Put business invariants in application/domain services, not controllers, DTO mappers, clients, or entity listeners.
- Prefer constructor injection; avoid field injection, static mutable state, and undocumented magic values.
- Use Java records for immutable transport types when appropriate; use explicit names and validation.
- Do not share JPA entities as public REST responses or across module boundaries.
- Keep transactional responsibilities in named application services. Be aware of Spring proxy/self-invocation limitations.
- Avoid circular module dependencies, generic “util” dumping grounds, and unnecessary abstraction layers.
- Keep API requests, persistence objects, and domain concepts distinct where their invariants differ.

## 5. Non-negotiable financial correctness rules

These requirements override generic coding patterns or skill recommendations.

1. **PHP only for the MVP.** Monetary values are integer minor units (centavos), stored as PostgreSQL `BIGINT`/Java `long` or a validated domain wrapper. Use checked arithmetic to detect overflow. Never use `float` or `double` for money.
2. **Wire format:** JSON monetary minor-unit values are decimal **strings** (for example, `"500000"` for ₱5,000.00) to avoid JavaScript integer precision loss. Validate parsing and bounds at the API boundary.
3. **Double-entry accounting:** every posted journal must balance debits and credits per currency. Posting one leg without its counterpart is forbidden.
4. **Immutability:** posted journals and ledger entries are append-only. Corrections require explicit reversing/new journal entries; never silently edit or delete posted accounting history.
5. **Account classification:** distinguish customer deposit liabilities from assets and clearing accounts. For an internal transfer, debit the sender's deposit liability and credit the recipient's deposit liability. Simulated funding must also balance against an explicitly modeled test clearing account.
6. **Atomicity:** for internal transfers, transfer state, journal, ledger entries, authoritative balance projection, idempotency outcome, and durable event record (if used) must commit as one PostgreSQL transaction, or none must commit.
7. **Available funds:** verify the source account's available balance under concurrency protection before committing. Never let simultaneous requests overspend an account.
8. **Lock discipline:** choose and document a safe strategy such as deterministic account row-lock ordering or a proven optimistic strategy with bounded retries. Test deadlock/rollback behavior; do not improvise locks without tests.
9. **Idempotency:** scope keys to the authenticated actor and operation; persist request fingerprint and outcome under an appropriate unique constraint. Same key + same logical request must not post twice; same key + different request must fail explicitly (e.g., HTTP 409).
10. **Unknown outcomes:** a client timeout is not proof that posting failed. Provide lookup/replay semantics to resolve the original attempt. Never instruct clients to retry a transfer using a freshly generated key merely because the response was lost.
11. **Ledger reconciliation:** balances and account projections must reconcile to posted ledger entries. Detection of discrepancies must not silently repair them.
12. **Auditability:** every financial posting and privileged correction/investigation must have a stable correlation/reference ID and attributable audit record.

Do not perform money movement from scheduled events, notification handlers, AI components, or frontend applications outside approved financial application services.

## 6. API and cross-repository contract

- This repository is the **contract authority**. Maintain the checked-in spec at `api/openapi/banking-v1.yaml` once the contract milestone creates it; do not fabricate the file before that milestone.
- API base path: `/api/v1`. Use explicit request/response DTOs, validation, versioned enums, and deterministic error codes.
- Use RFC 9457 Problem Details for error responses, consistent with the agreed contract. Do not invent a competing universal response envelope.
- Use appropriate `201`, `200`, `202`, `400`, `401`, `403`, `404`, `409`, `422`, and `5xx` outcomes according to the documented operation, not by habit.
- Pagination, sorting, and filtering must be bounded. Do not expose unbounded account/transaction data.
- Publish immutable or otherwise reproducibly pinned OpenAPI artifacts consumed by `banking-web` and `banking-mobile`.
- Prefer additive, backward-compatible changes. Treat installed mobile clients as potentially older than the currently deployed backend. A breaking contract change requires explicit versioning/migration and coordination.
- Do not add endpoints or fields based solely on an assumed UI need; document and approve cross-repository dependencies.

## 7. Authentication, authorization, and privacy

- Validate JWT signature, issuer, audience, expiration, and other applicable claims at the backend. Never trust unsigned or merely decoded JWT content.
- Map the identity-provider subject to an internal customer/operator identity. Do not trust a client-supplied customer ID, role, owner, or balance.
- Enforce **object-level authorization** on every customer account, transfer, statement, and receipt lookup. Enforce separate operator permissions for administrative APIs.
- Apply least privilege to roles, endpoints, database access, and any tooling. Prefer deny-by-default policies.
- Separate customer and operator authorization, even when both are served by the same API.
- Validate input at the boundary and domain invariant at the application layer. Prevent overposting/mass assignment.
- Never hardcode tokens, passwords, private keys, or production credentials. Use environment/secret injection and provide only safe example configuration.
- Do not log access tokens, secrets, full account identifiers, sensitive personal information, or raw payment payloads. Sanitize traces, errors, and audit views.
- Development-only mock-funding and fault-injection endpoints must be disabled or inaccessible in public deployments.
- Use synthetic data exclusively; protect the demo even though no real financial value exists.

## 8. Persistence, migrations, and reliability

- All schema changes go through **new, versioned Flyway migrations**. Do not edit applied migration files; never use Hibernate `ddl-auto=update` to manage schema.
- Use explicit constraints, foreign keys, unique indexes, currency/account checks, and appropriate transaction boundaries to enforce correctness in the database as well as in Java.
- Treat balance projections as derived from authoritative posted entries; update them atomically and provide a reproducible reconciliation mechanism.
- Keep database transactions small and deterministic. Do not hold locks during network calls or slow external I/O.
- Use durable transactional publication/outbox patterns for post-commit events where required; notification delivery must never repost the money movement.
- Retries must be bounded and applied only to operations safe to retry. Distinguish transient technical failures from permanent business rejections.
- Do not use `@Async`, `REQUIRES_NEW`, or retries around financial posting without explicitly proving the consistency semantics and tests.
- Document backup/restore assumptions and recovery processes when those features are introduced.

## 9. Testing and verification gates

Every domain or financial feature must add tests at the correct level. Prefer fast unit tests for pure rules, Spring slices for infrastructure boundaries, and PostgreSQL Testcontainers integration tests for transactional behavior.

At a minimum, cover when relevant:

- Balanced journals and immutable posted entries.
- Correct account classifications and balanced simulated funding.
- Concurrent transfers against insufficient aggregate funds.
- Concurrent identical idempotency keys and conflicting payload reuse.
- Transaction rollback before commit and response loss after commit.
- Overflow, negative/zero amounts, currency mismatch, closed/frozen accounts, and transfer limits.
- Cross-customer account access, operator authorization, and invalid/expired tokens.
- Reconciliation success and deliberately introduced mismatches.
- Interrupted background publication and safe redelivery (once implemented).
- Module dependency verification, schema migrations, contract compatibility, and API validation.

Use real PostgreSQL (not H2) for locking/isolation and ledger-integrity acceptance tests. Make parallel tests deterministic and diagnose flakiness rather than ignoring it. Do not weaken assertions or skip tests to make CI green.

Validation commands, depending on the checked-in build tooling:

```bash
./mvnw clean verify        # macOS/Linux/Git Bash if Maven Wrapper exists
mvn clean verify          # fallback if Maven is installed and no wrapper exists
```

On Windows Command Prompt or PowerShell, use `mvnw.cmd clean verify` if the wrapper exists.

Report tests and commands actually run. If containers, Docker, Keycloak, network, or credentials are unavailable, state the limitation and identify the unverified behaviors.

## 10. Git workflow, review, and documentation

- Protected integration branch: `main`. Never commit directly to it.
- Create short-lived branches from current `main`: `chore/*`, `feat/*`, `fix/*`, `test/*`, `refactor/*`, or `docs/*`.
- The initial scaffolding/foundation branch is `chore/backend-foundation`; keep banking business features for subsequent branches.
- Follow Conventional Commits (for example, `feat(ledger): add balanced journal posting`).
- Do not force-push protected branches, bypass required CI, or rewrite shared Git history.
- Never commit secrets, `.env` values, build artifacts, or generated files unless explicitly approved as tracked output.
- Do not automatically create commits, push, merge PRs, delete branches, or perform destructive Git commands unless explicitly requested.
- Each PR should state: scope, relevant issue, architectural impact, schema/API changes, security/financial impact, tests executed, known limitations, and rollback considerations.
- Record material architectural decisions in `docs/adr/` when established. Update README, API examples, and runbooks when behavior changes.

## 11. Spring Boot skill selection for Codex

If the [`rrezartprebreza/spring-boot-skills`](https://github.com/rrezartprebreza/spring-boot-skills) Spring Boot 4 skills are installed and accessible, inspect the **actual installed skill** before applying its patterns. Use only the skills relevant to the current work:

| Task | Preferred skill(s) |
| --- | --- |
| Architecture/module boundaries | `spring-modulith`, `domain-driven-design` |
| API contract and errors | `openapi-first`, `rest-api-conventions`, `problem-details-rfc9457` |
| PostgreSQL/JPA/migrations | `transactional-patterns`, `spring-data-jpa`, `flyway-migrations` |
| Internal transfer retries | `idempotency-patterns`, `transactional-patterns` |
| OAuth2/JWT/authorization | `oauth2-resource-server`, `spring-security-review` |
| Audit and operational telemetry | `security-audit-logging`, `production-observability` |
| Unit/integration verification | `testing-pyramid` |
| Future events/AI only when approved | `event-driven-messaging`, `spring-ai-integration`, `spring-ai-security` |

These skills are **optional guidance, not dependencies**, and their presence must not be assumed. Do not install external code/plugins, introduce a custom response wrapper, or override this project's financial invariants merely because a skill suggests it. If a skill conflicts with this file, flag the conflict and follow the stricter banking-specific requirement pending review.

## 12. Required agent handoff

At the end of each coding task, respond with:

1. **Implemented:** files/modules changed and intended behavior.
2. **Verified:** exact build/test commands and actual results.
3. **Contract/schema effects:** API changes, Flyway migrations, compatibility impact.
4. **Financial/security assessment:** which invariants and authorization paths were exercised.
5. **Open issues:** risks, assumptions, unverified cases, and next scoped milestone.

**Default principle:** financial correctness and security > architectural clarity > testability > performance optimization > feature breadth. Never trade a banking invariant for a quicker demo.
