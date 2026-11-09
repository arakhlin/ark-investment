# Testing

The final submission passed both GitHub Actions workflows:

- [CI](https://github.com/arakhlin/ark-investment/actions/runs/42705596451): Java unit and PostgreSQL integration tests, Node tests, and OpenAPI lint.
- [Fresh Docker Demo Acceptance](https://github.com/arakhlin/ark-investment/actions/runs/42705596389): clean checkout, fresh Docker startup, repeated startup, seed, live API verification, repeated seed and verification, and cleanup.

## Run Automated Tests

From the repository root, with Java 21, Node.js 22+, and Docker running:

**Java tests** (including PostgreSQL integration tests via Testcontainers):

```sh
./mvnw -B clean verify
```

**Node tests:**

```sh
node --test docs/api/monetary-validation.test.cjs docs/demo/fixtures.test.cjs docs/demo/start.test.cjs
```

**OpenAPI lint** (requires npm/npx):

```sh
npx --yes @redocly/cli@2.75.0 lint docs/api/openapi.yaml
```

## Run the Live Demo Verification

With Docker and Node.js 22+:

```sh
node docs/demo.cjs start
node docs/demo.cjs seed
node docs/demo.cjs verify
```

The verifier exercises authenticated endpoints, financial reporting, Client isolation, authorization, and audit behavior against PostgreSQL and Keycloak. Repeat `start`, `seed`, and `verify` to check rerun behavior.

Stop the demo without deleting data:

```sh
node docs/demo.cjs down
```

See [Getting Started](GETTING_STARTED.md) for setup and API examples. The demo is intended for local evaluation, not production deployment.
