# Getting Started

This guide explains how to run the Ark Investment API locally and explore its features using the command line or Postman.

The local demo uses Docker Compose to start the API, PostgreSQL, and Keycloak.

## Prerequisites

- Docker with Docker Compose v2
- Node.js 22+
- Postman (optional)

## Start the Application

Run the commands in this guide from the repository root using Bash or zsh.

```sh
node docs/demo.cjs start
```

This command starts the API, PostgreSQL, and Keycloak, waits for healthy services, runs database migrations, and provisions two Clients and seven users covering all six roles. It verifies authenticated API access without manual user creation or Keycloak configuration. Startup or provisioning failures produce a nonzero exit status. Repeating `start` preserves existing matching data and credentials.

Defaults are `DEMO_PROJECT=ark-demo-client-local`, `DEMO_API_PORT=18080`, and `DEMO_OIDC_PORT=19080`. If those ports are occupied, optionally export overrides before starting:

```sh
export DEMO_PROJECT=ark-demo-reviewer-local
export DEMO_API_PORT=28080
export DEMO_OIDC_PORT=29080
node docs/demo.cjs start
```

Choose free ports and keep the same environment for subsequent commands. Ports are fixed after project initialization; use a new project to change them. The existing `up` and `provision` commands remain available separately.

Financial demonstration data is optional and is not loaded by `start`:

```sh
node docs/demo.cjs seed
```

This adds three funds, five investors, eight participations, and forty transactions for each Client. The live fixture verifier below requires this optional seed step.

View the local service URLs and available identities:

```sh
node docs/demo.cjs info
```

## First-Time Startup

In a controlled run with no unrelated Docker containers running, setup with fresh volumes and credentials and **cached image layers completed in 49.30 seconds including seeding**. Startup through provisioning (`start` alone) took **48.49 seconds**; seeding remains a separate, optional command.

Truly uncached builds may take several minutes to download dependencies and build the API and Keycloak images. No uncached-build performance improvement has been demonstrated. Allow Docker memory headroom and avoid competing stacks when evaluating startup. See [Testing](TESTING.md) for measurement conditions and acceptance results.

## Explore the API

The demo CLI handles local Keycloak authentication.

```sh
node docs/demo.cjs call alpha-admin GET /api/v1/me
node docs/demo.cjs call ark-admin GET /api/v1/clients
```

Financial endpoints are scoped to a Client:

```text
/api/v1/clients/{clientId}/funds
/api/v1/clients/{clientId}/investors
/api/v1/clients/{clientId}/transactions
```

The complete API specification is available in [OpenAPI](api/openapi.yaml).

## Postman (Optional)

Import `postman/Ark-Investment-API.postman_collection.json` and `postman/Ark-Local-Demo.postman_environment.json`.

The committed environment contains no passwords or tokens. Demo credentials are generated locally and stored under `.ark-demo/` (excluded from Git). Import the collection and environment, select **Ark Local Demo**, and populate the seven `*_password` environment variables from your own generated demo configuration. The collection obtains access tokens using these credentials; run its folders sequentially, not concurrently. Never commit or share a populated environment file.

For the simplest reviewer walkthrough, use the demo CLI commands above: they authenticate automatically, without copying credentials into Postman.

## Financial Reporting Example

The Postman workflow creates five transactions:

| Type | Amount | Effect |
| --- | ---: | --- |
| Contribution | 1000.00 | Credit |
| Interest Income | 25.00 | Credit |
| Distribution | 100.00 | Debit |
| General Expense | 10.00 | Debit |
| Management Fee | 5.00 | Debit |

The resulting report has total credits of **1025.00**, total debits of **115.00**, and a closing balance of **910.00**.

## Verify and Stop

Run the live API verification:

```sh
node docs/demo.cjs verify
```

Stop the demo while preserving its database volumes:

```sh
node docs/demo.cjs down
```

Avoid `down -v` or Docker volume pruning unless you intentionally want to remove stored data.

For automated tests and recorded results, see [Testing](TESTING.md).

## Troubleshooting

| Problem | Check |
| --- | --- |
| Services fail to start | Docker status, container health, and available ports |
| HTTP 401 | Keycloak availability or expired token |
| HTTP 403/404 | User permissions, Client ownership, or request path |
| Postman authentication fails | Active environment and generated credentials |
| Missing demo data | Run optional `seed` after `start` for the same project |

The demo uses local HTTP and Keycloak password-grant authentication for development and evaluation only.
