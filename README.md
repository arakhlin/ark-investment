# Ark Investment API

A REST API for managing investment funds, investors, and financial transactions. Built with Java 21, Spring Boot, and PostgreSQL as a take-home engineering assignment.

The application supports client-level data isolation, role-based access control, financial reporting, and transaction auditing.

## Features

- **Funds and Investors:** CRUD operations and many-to-many participation.
- **Transactions:** Contributions, interest income, distributions, general expenses, and management fees.
- **Financial Reporting:** Fund and investor balances, transaction totals, and date-range reporting.
- **Client Isolation:** Financial data belongs to a specific Client.
- **Authentication and Authorization:** Keycloak JWT authentication and six database-managed roles.
- **Audit Trail:** Transaction changes and administrative actions are recorded.
- **API Documentation:** OpenAPI 3.0.3 specification.

## Technology Stack

| Component | Technology |
| --- | --- |
| Backend | Java 21, Spring Boot 4.1.2 |
| Persistence | Spring Data JPA, JDBC, PostgreSQL 17 |
| Database migrations | Flyway |
| Security | Spring Security, JWT, Keycloak |
| Build and testing | Maven, JUnit, Testcontainers |
| API contract | OpenAPI 3.0.3 |
| Local deployment | Docker Compose |
| CI | GitHub Actions |

## Architecture

The application follows a **Modular Monolith** architecture, with domain-oriented packages and a shared PostgreSQL database.

This approach keeps financial operations within local database transactions, simplifies deployment, and avoids unnecessary distributed-system complexity.

Key implementation decisions include:

- `BigDecimal` and PostgreSQL `NUMERIC(18,2)` for monetary precision.
- Database constraints and transactional locking for financial integrity.
- `REPEATABLE_READ` isolation for consistent financial reports.
- Database-backed authorization and Client-scoped queries.
- Transactional audit writes alongside business changes.

See [Architecture](docs/ARCHITECTURE.md) for the main design choices and trade-offs.

## Quick Start

**Prerequisites:** Running Docker with Docker Compose v2 and Node.js 22+. First startup requires internet access to download container images and build dependencies. Allow Docker memory headroom for the API, PostgreSQL, and Keycloak; concurrent stacks or builds can cause slow startup or out-of-memory failures. No minimum RAM requirement has been established.

From the repository root:

```sh
node docs/demo.cjs start
```

This command starts the API, PostgreSQL, and Keycloak, waits for healthy services, runs database migrations, and provisions two Clients and seven users for authenticated API access. Repeating it preserves existing matching data and credentials. Startup or provisioning failures produce a nonzero exit status.

Defaults: project `ark-demo-client-local`, API `http://localhost:18080`, and Keycloak `http://localhost:19080`. Override them with `DEMO_PROJECT`, `DEMO_API_PORT`, and `DEMO_OIDC_PORT` when needed. The separate standard local setup (`docker-compose.yml`, requiring external OIDC configuration) defaults to API port `8080`; the self-contained demo uses `18080`.

Financial demo data is optional and is not loaded by `start`:

```sh
node docs/demo.cjs seed
```

For authentication, Postman examples, verification, and cleanup instructions, see [Getting Started](docs/GETTING_STARTED.md).

## Testing

The latest documentation revision (`af08b5c`) passed [Build, tests and OpenAPI](https://github.com/arakhlin/ark-investment/actions/runs/42729283046): 165 Java tests, 163 Node tests, and OpenAPI validation.

The application revision (`e1c512c`) also passed [Fresh Docker Demo Acceptance](https://github.com/arakhlin/ark-investment/actions/runs/42728131595), manually dispatched on the feature branch: fresh startup, repeated startup, seeding, repeated live API verification, and cleanup. Subsequent commits changed documentation only.

Separately, a controlled local fresh-volume setup with cached image layers and no unrelated running containers took 49.30 seconds including seeding (48.49 seconds through provisioning). This is not an uncached-build measurement or a guarantee of reviewer startup time.

See [Testing](docs/TESTING.md) for validation details and reproduction commands.

## Documentation

- [Getting Started](docs/GETTING_STARTED.md) — Run and explore the API.
- [Architecture](docs/ARCHITECTURE.md) — Design choices and trade-offs.
- [Testing](docs/TESTING.md) — Test results and reproduction commands.
- [OpenAPI Specification](docs/api/openapi.yaml) — REST API contract.

## Scope and Limitations

This is a backend-focused demonstration, not a production deployment.

The financial model uses USD ledger balances rather than investment valuation or performance calculations. Transactions can be corrected or deleted, with audit records retained. Production requirements such as immutable accounting periods, backup/recovery, and operational security would require additional design decisions.
