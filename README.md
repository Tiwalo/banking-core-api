# Tiwalo Banking Core API

Java 25 and Spring Boot 4.1.2 backend for a simulated Philippine digital bank. Use synthetic identities and fictional money. The current foundation provides PostgreSQL integration and architecture verification. The planned module responsibilities are recorded in [ADR 0001](docs/adr/0001-modular-monolith.md).

## Verify the checkout

Install JDK 25 and start a Docker engine that can run Linux containers. From the repository root, run `./mvnw clean verify` on Linux or macOS, or `.\mvnw.cmd clean verify` in PowerShell. The wrapper downloads Maven on first use. Testcontainers starts its own PostgreSQL instance and supplies the connection details. The suite checks the application datasource and runs Spring Modulith boundary verification.

## Run the foundation locally

1. Set `SPRING_DATASOURCE_USERNAME` to a local application login such as `banking`. Set `SPRING_DATASOURCE_PASSWORD` for that login and `POSTGRES_PASSWORD` separately for PostgreSQL bootstrap. Supply distinct local secrets through your shell or secret manager. Keep the values for subsequent sessions using the same volume.
2. Run `docker compose up -d --wait postgres`. On the first start with an empty volume, PostgreSQL creates the application login with permission to create objects in the local database schema. Compose publishes the database on `127.0.0.1:5433`. If that port is occupied, set `TIWALO_POSTGRES_PORT` to a free port before starting Compose and the application.
3. In the same environment, run `./mvnw spring-boot:run` or `.\mvnw.cmd spring-boot:run`. The application listens on port 8081 and connects to database `banking_core`. Use `SPRING_DATASOURCE_URL` to select a different local instance.
4. Stop the application with Ctrl+C. Run `docker compose stop postgres` to stop the database and retain its data. Changing credentials on an initialized volume requires updating the database role as well as the shell environment.
