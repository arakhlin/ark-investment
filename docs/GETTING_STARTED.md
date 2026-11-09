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

## Postman

Import the collection and environment:

- `postman/Ark-Investment-API.postman_collection.json`
- `postman/Ark-Local-Demo.postman_environment.json`

The committed environment contains no credentials. Generate a local environment populated with demo passwords:

```sh
node <<'JS'
const fs = require('node:fs');
const path = require('node:path');
const demo = require('./docs/demo.cjs');

const config = demo.load();
const environment = JSON.parse(
  fs.readFileSync('postman/Ark-Local-Demo.postman_environment.json', 'utf8')
);

const values = new Map(environment.values.map(v => [v.key, v]));
values.get('api_url').value = `http://localhost:${config.apiPort}/api/v1`;
values.get('oidc_issuer').value = demo.issuer(config);

for (const value of environment.values.filter(v => v.key.endsWith('_username'))) {
  const password = config.userPasswords[value.value];
  if (!password) throw new Error(`Missing credentials for ${value.value}`);
  values.get(value.key.replace(/_username$/, '_password')).value = password;
}

const output = path.join('.ark-demo', config.project, 'postman.local.json');
fs.writeFileSync(output, JSON.stringify(environment, null, 2) + '\n', { mode: 0o600 });
fs.chmodSync(output, 0o600);
console.log(output);
JS
```

Import the generated `.ark-demo/<project>/postman.local.json` and select it as the active environment.

This file contains plaintext demo passwords. Keep it local and do not commit or share it.

Run the Postman collection folders in order to exercise authentication, administration, financial operations, reporting, authorization, and cleanup.

Alternatively, run the full collection using Newman:

```sh
npx --yes newman@6.2.1 run postman/Ark-Investment-API.postman_collection.json \
  --environment ".ark-demo/${DEMO_PROJECT:-ark-demo-client-local}/postman.local.json" \
  --bail --reporters cli --timeout-request 20000
```

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