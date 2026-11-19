# Tiwalo Banking Core API

Java 25 and Spring Boot 4.1.2 backend for a simulated Philippine digital bank. Use synthetic identities and fictional money. Customers can list and read their own PHP deposit accounts. The database enforces a zero opening balance until ledger-backed posting ships. See [ADR 0001](docs/adr/0001-modular-monolith.md) for module ownership.

## Banking v1 contract

The [OpenAPI 3.1 contract](api/openapi/banking-v1.yaml) defines customer account reads, internal transfer submission, same-key replay, and receipt lookup. The two account reads are available. Transfer submission and receipts remain planned.

For a new client version, `banking-web` and `banking-mobile` should pin the full backend commit SHA and the path `api/openapi/banking-v1.yaml` from a verified merge to `main`. The **contract** CI job links that immutable revision in its push summary. In a backend checkout, `git show <full-commit-sha>:api/openapi/banking-v1.yaml` retrieves the corresponding document. A client can keep its existing pin through later additive updates.

Run `python scripts/verify_contract.py origin/main` from the repository root with Python 3 and Docker running (use `python3` on Linux). CI lints the document and validates its examples, then checks for backward-incompatible changes against the PR base or the previous published main tip. The first contract passes the comparison stage when the verified base commit has no v1 document. Review changes to ownership, decline codes, and retry semantics as well, since a structural diff cannot establish those behaviors.

## Verify the checkout

Install JDK 25 and start a Docker engine that can run Linux containers. From the repository root, run `./mvnw clean verify` on Linux or macOS, or `.\mvnw.cmd clean verify` in PowerShell. The wrapper downloads Maven on first use. Testcontainers starts its own PostgreSQL instance and supplies the connection details. The suite checks the application datasource and runs Spring Modulith boundary verification.

## Run accounts and identity locally

1. Set `SPRING_DATASOURCE_USERNAME` to a local application login such as `banking`. Set `SPRING_DATASOURCE_PASSWORD` for that login and `POSTGRES_PASSWORD` separately for PostgreSQL bootstrap. Supply distinct local secrets through your shell or secret manager. Keep the values for subsequent sessions using the same volume.
2. Set a local `KC_BOOTSTRAP_ADMIN_PASSWORD` before first starting Keycloak. Run `docker compose up -d postgres keycloak`. PostgreSQL listens on loopback port 5433 and Keycloak on loopback port 8180. Set `TIWALO_POSTGRES_PORT` or `TIWALO_KEYCLOAK_PORT` in both shells if a port is occupied. Each service retains its data in its own local volume.
3. Set `SPRING_PROFILES_ACTIVE=local` in the application shell and run `./mvnw spring-boot:run` or `.\mvnw.cmd spring-boot:run`. Flyway creates the identity and account tables. The API listens on port 8081. Outside the local profile, configure the trusted `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUERURI` for your issuer. The default JWKS path is Keycloak's certs endpoint under that issuer.
4. In the Keycloak admin console, select the imported `tiwalo` realm. Under **Users → demo-customer → Credentials**, set a local password and turn **Temporary** off. The optional `demo-operator` has a separate role. Realm import runs on first initialization and preserves existing realms on restart.
5. Seed the synthetic account once the API has started. In PowerShell, run the following from the repository root with the same environment as Compose.

```powershell
$issuerPort = if ($env:TIWALO_KEYCLOAK_PORT) { $env:TIWALO_KEYCLOAK_PORT } else { '8180' }
$issuer = "http://localhost:$issuerPort/realms/tiwalo"
Get-Content -Raw dev/postgres/seed-identity.sql | docker compose exec -T postgres psql -U $env:SPRING_DATASOURCE_USERNAME -d banking_core -v "issuer=$issuer"
```

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

Stop the app with Ctrl+C and run `docker compose stop postgres keycloak` to retain the local data. Preserve the additive V1 tables and Flyway history during an application rollback.
