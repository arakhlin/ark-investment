# Testing

Ark Investment API includes automated tests for business logic, database integrity, authorization, financial reporting, and API behavior.

## Prerequisites

- Java 21
- Docker with Docker Compose
- Node.js 22+
- Ruby with YAML support

Docker must be running for PostgreSQL integration tests.

## 1. Java Tests

Run the complete Maven verification lifecycle from the repository root:

```sh
./mvnw -B clean verify
```

The suite includes:

- Unit tests for business rules and validation.
- PostgreSQL integration tests using Testcontainers.
- Transaction concurrency and rollback tests.
- Client isolation and role-based authorization tests.
- Financial reporting consistency tests.

## 2. Contract and Fixture Tests

Run the Node.js tests:

```sh
node --test docs/api/monetary-validation.test.cjs docs/demo/fixtures.test.cjs
```

These validate monetary constraints, API contract expectations, and deterministic demo fixtures.

## 3. OpenAPI Validation

Validate the API specification:

```sh
npx --yes @redocly/cli@2.75.0 lint docs/api/openapi.yaml
```

The last local validation passed with two non-blocking warnings: `info-license` for missing license metadata and `no-server-example.com` for the localhost server URL.

## 4. Live API Verification

Start and seed the local environment as described in [Getting Started](GETTING_STARTED.md).

Then run:

```sh
node docs/demo.cjs verify
```

The live verifier exercises authenticated API operations, financial calculations, Client isolation, authorization, and audit behavior against PostgreSQL and Keycloak.

## 5. Local Acceptance Evidence

The following local acceptance results were recorded on November 9, 2026 against source revision `f887112185b46b3df4cee87930ba0a9a245b77cb`, using separate clean local Git clones:

| Check | Environment | Recorded result |
| --- | --- | --- |
| Maven verification | macOS ARM64; Microsoft OpenJDK 21.0.12.1; Maven 3.10.0; PostgreSQL Testcontainers | 165 passed; no failures, errors, or skips |
| Node suites and OpenAPI lint | Node 22.19.0; Ruby 2.6.10; Redocly 2.75.0 | 126 tests passed; OpenAPI valid with the two warnings above |
| Live demo and retained-volume restart | Docker Desktop; Java 21 API, PostgreSQL 17, Keycloak | Verifier passed before and after restart; 184 exact reports per pass; stored counts and digests preserved |
| Postman / Newman | Same isolated demo; Newman 6.2.1 | 58 requests, 125 assertions, no failures |

The acceptance record was committed as `464b30a` in the Development Repository. These were local clones using available dependency/image caches, not clones from the final Submission Repository.

## 6. Continuous Integration

GitHub Actions is configured in `.github/workflows/ci.yml` to run automated checks.

Local isolated-clone acceptance is complete for the revision above. GitHub Actions execution and a fresh-clone walkthrough from the final Submission Repository remain pending.

## Scope

These tests provide evidence for the implemented API behavior. They do not establish production readiness, load-test capacity, or complete security coverage.

The local demo is intended for reproducible evaluation, not production deployment.