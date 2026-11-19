# Tiwalo Banking Core API

Java 25 and Spring Boot 4.1.2 backend for a simulated Philippine digital bank. Use synthetic identities and fictional money. Customers can list and read their own PHP deposit accounts. Accounts open at zero. Both account reads show balances from posted journals, in integer centavos encoded as decimal strings. [Module ownership](docs/adr/0001-modular-monolith.md) · [Ledger posting](docs/adr/0003-ledger-posting.md)

## Banking v1 contract

The [OpenAPI 3.1 contract](api/openapi/banking-v1.yaml) covers customer account reads, internal transfers, same-key replay, owner-scoped receipts, and operator reconciliation.

For a new client version, `banking-web` and `banking-mobile` should pin the full backend commit SHA and the path `api/openapi/banking-v1.yaml` from a verified merge to `main`. The **contract** CI job links that immutable revision in its push summary. In a backend checkout, `git show <full-commit-sha>:api/openapi/banking-v1.yaml` retrieves the corresponding document. A client can keep its existing pin through later additive updates.

Run `python scripts/verify_contract.py origin/main` from the repository root with Python 3 and Docker running (use `python3` on Linux). CI lints the document and validates its examples, then checks for backward-incompatible changes against the PR base or the previous published main tip. The first contract passes the comparison stage when the verified base commit has no v1 document. Review changes to ownership, decline codes, and retry semantics as well, since a structural diff cannot establish those behaviors.

## Verify the checkout

Install JDK 25 and start a Docker engine that can run Linux containers. From the repository root, run `./mvnw clean verify` on Linux or macOS, or `.\mvnw.cmd clean verify` in PowerShell. The wrapper downloads Maven on first use. Testcontainers starts its own PostgreSQL instance and supplies the connection details. The suite checks the application datasource and runs Spring Modulith boundary verification.

## Run accounts and identity locally

1. Set `SPRING_DATASOURCE_USERNAME` to a restricted API login such as `tiwalo_api`. Set `TIWALO_MIGRATION_USER` to a separate schema owner such as `banking`. Supply distinct local secrets for `SPRING_DATASOURCE_PASSWORD`, `TIWALO_MIGRATION_PASSWORD`, `TIWALO_FUNDING_PASSWORD` and `POSTGRES_PASSWORD` through your shell or secret manager. Keep existing passwords when reusing a volume. For a V1 volume, use its original application login and password as the migration owner and choose a fresh API login.
2. Set a local `KC_BOOTSTRAP_ADMIN_PASSWORD` before first starting Keycloak. Run `docker compose up -d postgres keycloak`. PostgreSQL listens on loopback port 5433 and Keycloak on loopback port 8180. Set `TIWALO_POSTGRES_PORT` or `TIWALO_KEYCLOAK_PORT` in both shells if a port is occupied. Each service retains its data in its own local volume.
3. Build the jar and migrate the database once per schema release. On an **existing V1 volume**, first run `docker compose exec -T postgres psql -U postgres -d banking_core -f /docker-entrypoint-initdb.d/10-application-role.sql` after Compose has recreated the container with the new environment. Fresh volumes run this role setup automatically. In PowerShell run the following. The single quotes pass secret references to Spring without expanding passwords into the command line.

   ```powershell
   .\mvnw.cmd clean verify
   $jar = 'target/banking-core-api-0.0.1-SNAPSHOT.jar'
   java -jar $jar --spring.profiles.active=local-migrate '--spring.datasource.username=${TIWALO_MIGRATION_USER}' '--spring.datasource.password=${TIWALO_MIGRATION_PASSWORD}'
   ```

   In a separate API shell, set its restricted datasource credentials, local port settings and a positive per-transfer cap in PHP centavos. For example, `$env:TIWALO_TRANSFER_CAP_MINOR = '500000'` sets a simulated limit of ₱5,000 per transfer. The HTTP service requires an explicit cap. Run `java -jar target/banking-core-api-0.0.1-SNAPSHOT.jar --spring.profiles.active=local`. The API listens on port 8081. Outside the local profile, configure `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUERURI` for the trusted issuer. Flyway runs in the migration process.
4. In the Keycloak admin console, select the imported `tiwalo` realm. Under **Users → demo-customer → Credentials**, set a local password and turn **Temporary** off. The optional `demo-operator` has a separate role. Realm import runs on first initialization and preserves existing realms on restart.
   For an existing realm, add the `transfers:create` and `transfers:read` client roles to `banking-core-api`, assign both to `demo-customer`, and obtain a fresh token. Fresh imports include both roles.
5. Seed the synthetic account once the API has started. In PowerShell, run the following from the repository root with the same environment as Compose.

```powershell
$issuerPort = if ($env:TIWALO_KEYCLOAK_PORT) { $env:TIWALO_KEYCLOAK_PORT } else { '8180' }
$issuer = "http://localhost:$issuerPort/realms/tiwalo"
Get-Content -Raw dev/postgres/seed-identity.sql | docker compose exec -T postgres psql -U $env:TIWALO_MIGRATION_USER -d banking_core -v "issuer=$issuer"
```

### Post simulated funding

After seeding, run the following from the setup shell. This non-web command authenticates as the dedicated local funding login. The example funds the seeded deposit account with 50,000 centavos. It prints the committed journal ID.

```powershell
java -jar $jar --spring.profiles.active=local-funding --spring.datasource.username=tiwalo_local_seed '--spring.datasource.password=${TIWALO_FUNDING_PASSWORD}' --funding.reference=b3095e34-c825-4a5c-b52d-b19bf15048f5 --funding.clearing=94ba5051-0214-45ef-bbd4-89fe108f2658 --funding.deposit=9e4777f9-8de5-462c-8366-9d8aa9766096 --funding.amount-minor=50000
```

Retry the identical command after a timeout or lost response. The same funding identity, reference and request return the original journal, including after an account changes status. Reusing the reference with a different request fails. Assign a fresh reference only for an intentional additional funding operation. Keep the funding actor UUID when rotating its login. Public deployments provision the empty `tiwalo_dev_funder` privilege group required by Flyway and omit funding logins and seed data.

### Read an account with a local token

The public `tiwalo-local` client uses the device authorization flow. Run this in the PowerShell session with `$issuer` set. Open the verification link, confirm the displayed code, and sign in as `demo-customer`. After approval, run the second block before the device code expires.

```powershell
$oidc = Invoke-RestMethod "$issuer/.well-known/openid-configuration"
$device = Invoke-RestMethod -Method Post -Uri $oidc.device_authorization_endpoint -Body @{client_id='tiwalo-local'}
$device | Select-Object user_code, verification_uri_complete
```

```powershell
$token = Invoke-RestMethod -Method Post -Uri $oidc.token_endpoint -Body @{
    client_id='tiwalo-local'
    grant_type='urn:ietf:params:oauth:grant-type:device_code'
    device_code=$device.device_code
}
$headers = @{Authorization="Bearer $($token.access_token)"}
$page = Invoke-RestMethod -Headers $headers -Uri 'http://localhost:8081/api/v1/accounts'
Invoke-RestMethod -Headers $headers -Uri "http://localhost:8081/api/v1/accounts/$($page.accounts[0].accountId)"
```

The API checks the token signature, issuer, `banking-core-api` audience, expiry, subject, and Keycloak bearer token type. Account reads require the API client role `accounts:read` and a customer mapping for the verified issuer and subject. Every account query is scoped to that customer. Page cursors identify a continuation within the same customer's accounts. [Identity and opening-balance decision](docs/adr/0002-identity-and-unfunded-accounts.md)

### Send an internal transfer

Apply V3, rerun the local seed, fund the sender, and obtain a customer token as above. Create one intent and save its key and payload together. This example sends ₱10 to the seeded synthetic payee.

```powershell
$key = [guid]::NewGuid().ToString()
$paymentHeaders = @{Authorization=$headers.Authorization; 'Idempotency-Key'=$key}
$payload = @{
    sourceAccountId='9e4777f9-8de5-462c-8366-9d8aa9766096'
    recipientReference='rcp_24bf04e6bd8b4a39bbd3c8f3a8fa1245'
    currency='PHP'
    amountMinor='1000'
} | ConvertTo-Json
```

```powershell
$receipt = Invoke-RestMethod -Method Post -Uri 'http://localhost:8081/api/v1/transfers' -Headers $paymentHeaders -ContentType 'application/json' -Body $payload
Invoke-RestMethod -Headers $headers -Uri "http://localhost:8081/api/v1/transfers/$($receipt.transferId)"
```

After a timeout, lost response or `503 TRANSFER_BUSY`, repeat the POST with the saved key and original payload. A posted transfer returns the original `201`, receipt and Location. A definite `422` decline also replays. Changed input with the same key returns `409`. [Atomic transfer decision](docs/adr/0004-atomic-internal-transfers.md)

### Reconcile the ledger as an operator

Apply V4 and the local identity seed. Set a local password for `demo-operator` in Keycloak, then complete a fresh device login as that operator. The API requires the `operations:investigate` role and the internal operator mapping. In the shell containing the operator's token, run the following.

```powershell
$operatorHeaders = @{Authorization="Bearer $($token.access_token)"}
$reports = 'http://localhost:8081/api/v1/operations/reconciliations'
$report = Invoke-RestMethod -Method Post -Uri $reports -Headers $operatorHeaders
$report | Select-Object runId, startedAt, findingCount
$findingsUri = "$reports/$($report.runId)/findings"
$page = Invoke-RestMethod -Uri "${findingsUri}?limit=20" -Headers $operatorHeaders
$page.findings
while ($page.nextCursor) {
    $page = Invoke-RestMethod -Uri "${findingsUri}?limit=20&cursor=$($page.nextCursor)" -Headers $operatorHeaders
    $page.findings
}
```

A count of zero means the snapshot passed every check. Use the run ID and finding reference when escalating a discrepancy. The report preserves the original evidence as new transfers post. Every capture and findings lookup records the operator who performed it. [Reconciliation decision](docs/adr/0005-reconciliation-investigation.md)

Stop the app with Ctrl+C and run `docker compose stop postgres keycloak` to retain the local data. Preserve financial records, idempotency keys, receipts, reports and audit history during a rollout or rollback. Apply a forward migration for schema fixes.
