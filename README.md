# Tiwalo Banking Core API

Java 25 and Spring Boot 4.1.2 backend for a simulated Philippine digital bank. Use synthetic identities and fictional money. The current foundation provides PostgreSQL integration and architecture verification. The planned module responsibilities are recorded in [ADR 0001](docs/adr/0001-modular-monolith.md).

## Banking v1 contract

The [OpenAPI 3.1 contract](api/openapi/banking-v1.yaml) defines customer account reads, internal transfer submission, same-key replay, and receipt lookup. All four operations are currently marked `x-implementation-status: planned`. Promote an operation to `available` in the change that passes its HTTP and authorization contract tests.

For a new client version, `banking-web` and `banking-mobile` should pin the full backend commit SHA and the path `api/openapi/banking-v1.yaml` from a verified merge to `main`. The **contract** CI job links that immutable revision in its push summary. In a backend checkout, `git show <full-commit-sha>:api/openapi/banking-v1.yaml` retrieves the corresponding document. A client can keep its existing pin through later additive updates.

Run `python scripts/verify_contract.py origin/main` from the repository root with Python 3 and Docker running (use `python3` on Linux). CI lints the document and validates its examples, then checks for backward-incompatible changes against the PR base or the previous published main tip. The first contract passes the comparison stage when the verified base commit has no v1 document. Review changes to ownership, decline codes, and retry semantics as well, since a structural diff cannot establish those behaviors.

## Verify the checkout

Install JDK 25 and start a Docker engine that can run Linux containers. From the repository root, run `./mvnw clean verify` on Linux or macOS, or `.\mvnw.cmd clean verify` in PowerShell. The wrapper downloads Maven on first use. Testcontainers starts its own PostgreSQL instance and supplies the connection details. The suite checks the application datasource and runs Spring Modulith boundary verification.

## Run the foundation locally

1. Set `SPRING_DATASOURCE_USERNAME` to a local application login such as `banking`. Set `SPRING_DATASOURCE_PASSWORD` for that login and `POSTGRES_PASSWORD` separately for PostgreSQL bootstrap. Supply distinct local secrets through your shell or secret manager. Keep the values for subsequent sessions using the same volume.
2. Run `docker compose up -d --wait postgres`. On the first start with an empty volume, PostgreSQL creates the application login with permission to create objects in the local database schema. Compose publishes the database on `127.0.0.1:5433`. If that port is occupied, set `TIWALO_POSTGRES_PORT` to a free port before starting Compose and the application.
3. In the same environment, run `./mvnw spring-boot:run` or `.\mvnw.cmd spring-boot:run`. The application listens on port 8081 and connects to database `banking_core`. Use `SPRING_DATASOURCE_URL` to select a different local instance.
4. Stop the application with Ctrl+C. Run `docker compose stop postgres` to stop the database and retain its data. Changing credentials on an initialized volume requires updating the database role as well as the shell environment.
