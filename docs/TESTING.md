# Testing

This document explains how to reproduce the automated checks and verify the running demo.

## Prerequisites

- Java 21 for Maven tests
- Docker Engine running with Docker Compose v2
- Node.js 22+ (including npm/npx)
- Ruby with YAML support for the CI YAML check

The first run requires internet access to download dependencies and container images. Ensure Docker has enough memory for PostgreSQL, Keycloak, and the API; running multiple stacks concurrently can cause timeouts or out-of-memory failures.

## Automated Checks

Run from the repository root.

**Java unit and PostgreSQL integration tests** (Testcontainers requires Docker):

```sh
./mvnw -B clean verify
```

**Node contract, fixture, and startup/retry tests:**

```sh
node --test docs/api/monetary-validation.test.cjs docs/demo/fixtures.test.cjs docs/demo/start.test.cjs
```

**OpenAPI lint:**

```sh
npx --yes @redocly/cli@2.75.0 lint docs/api/openapi.yaml
```

GitHub Actions runs the automated checks in [ci.yml](../.github/workflows/ci.yml). The [latest Submission CI run](https://github.com/arakhlin/ark-investment/actions/runs/42700022918) passed on commit `db7d432`. It includes 165 Java tests and 158 Node tests, plus OpenAPI lint. Redocly reports two non-blocking warnings about license metadata and the local server URL.

## Live Demo Verification

Start and seed the application following [Getting Started](GETTING_STARTED.md):

```sh
node docs/demo.cjs start
node docs/demo.cjs seed
node docs/demo.cjs verify
```

The live verifier checks authenticated API operations, financial calculations, Client isolation, role-based authorization, and audit behavior against PostgreSQL and Keycloak.

The demo can be stopped without deleting its data:

```sh
node docs/demo.cjs down
```

## Verification Status and Limits

The latest Submission CI passed. Earlier isolated local runs also passed live API verification, but **a complete fresh-clone Docker acceptance was not repeated on the final implementation commit**. CI does not run the full Keycloak demo or Postman/Newman acceptance workflow. Do not interpret CI success as proof of a clean first-run Docker deployment.

The demo is designed for local evaluation, not production use. These tests do not establish production readiness, load capacity, or complete security coverage.
